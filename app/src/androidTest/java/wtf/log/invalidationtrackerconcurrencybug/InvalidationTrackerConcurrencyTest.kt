package wtf.log.invalidationtrackerconcurrencybug

import androidx.room.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Demonstrates a concurrency bug in [InvalidationTracker]. See the README for a full explanation.
 */
@RunWith(AndroidJUnit4::class)
class InvalidationTrackerConcurrencyTest {

    private lateinit var executor: ExecutorService
    private lateinit var database: SampleDatabase
    private lateinit var terminationSignal: AtomicBoolean

    @Before
    fun setup() {
        val applicationContext = InstrumentationRegistry.getInstrumentation().targetContext
        val threadId = AtomicInteger()
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply {
                name = "invalidation_tracker_test_worker_${threadId.getAndIncrement()}"
            }
        }
        database = Room
            .databaseBuilder(applicationContext, SampleDatabase::class.java, DB_NAME)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        terminationSignal = AtomicBoolean()
    }

    @After
    fun tearDown() {
        terminationSignal.set(true)
        executor.shutdown()
        val terminated = executor.awaitTermination(1L, TimeUnit.SECONDS)
        database.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DB_NAME)
        check(terminated)
    }

    @Test
    fun test() {
        val database = database
        val invalidationTracker = database.invalidationTracker
        val executor = executor
        val terminationSignal = terminationSignal

        // Launch CONCURRENCY number of tasks which stress the InvalidationTracker by repeatedly
        // registering and unregistering observers.
        repeat(CONCURRENCY) {
            executor.execute(StressRunnable(invalidationTracker, terminationSignal))
        }

        // Repeatedly, CHECK_ITERATIONS number of times:
        // 1. Add an observer
        // 2. Insert an entity
        // 3. Delete the entity
        // 4. Remove the observer
        // 5. Assert that the observer received exactly two invalidation calls.
        val dao = database.sampleDao
        val checkObserver = TestObserver()
        repeat(CHECK_ITERATIONS) {
            val invalidationCount = checkObserver.invalidationCount
            invalidationTracker.addObserver(checkObserver)
            try {
                val entity = SampleEntity(UUID.randomUUID().toString())
                dao.insert(entity)
                dao.delete(entity)
            } finally {
                invalidationTracker.removeObserver(checkObserver)
            }
            val actualCount = invalidationCount.get()
            assertEquals(2, actualCount, "iteration $it")
            invalidationCount.set(0)
        }
    }

    /**
     * Stresses the invalidation tracker by repeatedly adding and removing an observer.
     * @property invalidationTracker the invalidation tracker
     * @property terminationSignal when set to true, signals the loop to terminate
     */
    private class StressRunnable(
        private val invalidationTracker: InvalidationTracker,
        private val terminationSignal: AtomicBoolean,
    ) : Runnable {

        val observer = TestObserver()

        override fun run() {
            while (!terminationSignal.get()) {
                invalidationTracker.addObserver(observer)
                invalidationTracker.removeObserver(observer)
            }
        }
    }

    /**
     * A test observer which counts the number of calls to [onInvalidated].
     */
    private class TestObserver : InvalidationTracker.Observer(SampleEntity::class.java.simpleName) {

        val invalidationCount = AtomicInteger()

        override fun onInvalidated(tables: MutableSet<String>) {
            invalidationCount.incrementAndGet()
        }
    }

    companion object {

        private const val DB_NAME = "sample.db"

        /**
         * Change [CONCURRENCY] to 0 and the problem goes away.
         */
        private const val CONCURRENCY = 4
        private const val CHECK_ITERATIONS = 500
    }
}

@Database(
    entities = [SampleEntity::class],
    version = 1
)
abstract class SampleDatabase : RoomDatabase() {

    abstract val sampleDao: SampleDao
}

@Dao
interface SampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(count: SampleEntity)

    @Delete
    fun delete(count: SampleEntity)
}

@Entity
class SampleEntity(
    @PrimaryKey val id: String,
)

