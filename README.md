InvalidationTrackerConcurrencyBug
===

This is a sample project my bug report about a concurrency issue in Room's `InvalidationTracker`.

## Background

We noticed an issue in production where sometimes `Observable`s and `Flow`s returned by Room `Dao`s  would sometimes fail to emit new items when the table(s) were updated. This problem was common to both `Observable` and `Flow`, so we narrowed it down to the `InvalidationTracker`.

I added a breakpoint to each of the start and end of [this block of code](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:room/room-runtime/src/main/java/androidx/room/InvalidationTracker.java;l=553-567;drc=a277675436a63daa51b1ce9e70e5ba2905867969) which console logged the thread name and either `START` or `END`, and observed that multiple threads appear to be syncing the triggers concurrently. This was the output during a successful attempt to reproduce the issue:

```
arch_disk_io_0 BEGIN
arch_disk_io_0 END
arch_disk_io_1 BEGIN
arch_disk_io_3 BEGIN
arch_disk_io_2 BEGIN
arch_disk_io_0 BEGIN
arch_disk_io_1 END
arch_disk_io_3 END
arch_disk_io_2 END
arch_disk_io_0 END
arch_disk_io_0 BEGIN
arch_disk_io_0 END
```

The overlap in threads here was concerning and indicated to me that this is a likely culprit of the behavior we were seeing.

What's interesting is that I [reported kind-of the inverse of this issue back in 2018](https://issuetracker.google.com/issues/117900450) in which there was unnecessary contention on the close lock. With regard to removing the exclusive lock that previously existed in `InvalidationTracker`, a Google engineer said:

> This change should be safe since the InvalidationTracker and the ObservedTableTracker already had mechanisms for allowing concurrent calls of syncTriggers(). Specifically once a single thread gets the tables to sync all other threads exit out of syncing.

As far as I can tell, that doesn't seem to be happening I _think_ what he's referring to (and I could be wrong) is [this check](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:room/room-runtime/src/main/java/androidx/room/InvalidationTracker.java;l=535-538;drc=a277675436a63daa51b1ce9e70e5ba2905867969) at the start of the method for whether the db is already in a transaction. However, I don't think this check is working as intended because it's still race-y: multiple threads could pass this check around the same time before [reaching](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:room/room-runtime/src/main/java/androidx/room/InvalidationTracker.java;l=553;drc=a277675436a63daa51b1ce9e70e5ba2905867969) `beginTransactionInternal`.

## This Sample Project

This sample project reproduces the issue with [an instrumentation test](app/src/androidTest/java/wtf/log/invalidationtrackerconcurrencybug/InvalidationTrackerConcurrencyTest.kt) (see `InvalidationTrackerConcurrencyTest`) that does the following:
- Launches a number of concurrent "stress" tasks which repeatedly register and unregister observers from an `InvalidationTracker`
- Concurrently with those tasks, repeatedly:
  1. Adds an observer
  2. Inserts an entity
  3. Deletes the entity
  4. Removes the observer
  5. Asserts that the observer received exactly two invalidation calls.

On my machine, the test usually fails in under 100 iterations.
