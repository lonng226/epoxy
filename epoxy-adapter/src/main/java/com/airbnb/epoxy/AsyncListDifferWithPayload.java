package com.airbnb.epoxy;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.util.DiffUtil;
import android.support.v7.util.DiffUtil.ItemCallback;
import android.support.v7.util.ListUpdateCallback;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * An adaptation of Google's {@link android.support.v7.recyclerview.extensions.AsyncListDiffer}
 * that adds support for payloads in changes.
 *
 * Also adds support for canceling an in progress diff.
 */
class AsyncListDifferWithPayload<T> {
  private final static Executor DIFF_EXECUTOR = Executors.newFixedThreadPool(2);
  private final static Executor MAIN_THREAD_EXECUTOR = new MainThreadExecutor();
  private final ListUpdateCallback updateCallback;
  private final ItemCallback<T> diffCallback;

  AsyncListDifferWithPayload(@NonNull ListUpdateCallback listUpdateCallback,
      @NonNull DiffUtil.ItemCallback<T> diffCallback) {
    updateCallback = listUpdateCallback;
    this.diffCallback = diffCallback;
  }

  @Nullable
  private List<T> list;

  /**
   * Non-null, unmodifiable version of list.
   * <p>
   * Collections.emptyList when list is null, wrapped by Collections.unmodifiableList otherwise
   */
  @NonNull
  private List<T> readOnlyList = Collections.emptyList();

  // Max generation of currently scheduled runnable
  private int maxScheduledGeneration;
  private int maxFinishedGeneration;

  /**
   * Get the current List - any diffing to present this list has already been computed and
   * dispatched via the ListUpdateCallback.
   * <p>
   * If a <code>null</code> List, or no List has been submitted, an empty list will be returned.
   * <p>
   * The returned list may not be mutated - mutations to content must be done through
   * {@link #submitList(List)}.
   *
   * @return current List.
   */
  @NonNull
  public List<T> getCurrentList() {
    return readOnlyList;
  }

  /**
   * Prevents any ongoing diff from dispatching results. Returns true if there was an ongoing
   * diff to cancel, false otherwise.
   */
  public boolean cancelDiff() {
    boolean diffInProgress = maxScheduledGeneration > maxFinishedGeneration;
    maxFinishedGeneration = maxScheduledGeneration;
    return diffInProgress;
  }

  /**
   * Pass a new List to the AdapterHelper. Adapter updates will be computed on a background
   * thread.
   * <p>
   * If a List is already present, a diff will be computed asynchronously on a background thread.
   * When the diff is computed, it will be applied (dispatched to the {@link ListUpdateCallback}),
   * and the new List will be swapped in.
   *
   * @param newList The new List.
   */
  @SuppressWarnings("WeakerAccess")
  public void submitList(final List<T> newList) {
    if (newList == list) {
      // nothing to do
      return;
    }

    // incrementing generation means any currently-running diffs are discarded when they finish
    final int runGeneration = ++maxScheduledGeneration;

    if (newList == null) {
      //noinspection ConstantConditions
      updateCallback.onRemoved(0, list.size());
      list = null;
      readOnlyList = Collections.emptyList();
      return;
    }

    if (list == null) {
      // fast simple first insert
      updateCallback.onInserted(0, newList.size());
      list = newList;
      readOnlyList = Collections.unmodifiableList(newList);
      return;
    }

    final DiffCallback<T> wrappedCallback = new DiffCallback<>(list, newList, diffCallback);

    DIFF_EXECUTOR.execute(new Runnable() {
      @Override
      public void run() {
        final DiffUtil.DiffResult result = DiffUtil.calculateDiff(wrappedCallback);

        MAIN_THREAD_EXECUTOR.execute(new Runnable() {
          @Override
          public void run() {
            if (maxScheduledGeneration == runGeneration && runGeneration > maxFinishedGeneration) {
              latchList(wrappedCallback.newList, result);
              maxFinishedGeneration = runGeneration;
            }
          }
        });
      }
    });
  }

  private void latchList(@NonNull List<T> newList, @NonNull DiffUtil.DiffResult diffResult) {
    diffResult.dispatchUpdatesTo(updateCallback);
    list = newList;
    readOnlyList = Collections.unmodifiableList(newList);
  }

  private static class MainThreadExecutor implements Executor {
    final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void execute(@NonNull Runnable command) {
      handler.post(command);
    }
  }

  private static class DiffCallback<T> extends DiffUtil.Callback {

    final List<T> oldList;
    final List<T> newList;
    private final ItemCallback<T> diffCallback;

    DiffCallback(List<T> oldList, List<T> newList, ItemCallback<T> diffCallback) {
      this.oldList = oldList;
      this.newList = newList;
      this.diffCallback = diffCallback;
    }

    @Override
    public int getOldListSize() {
      return oldList.size();
    }

    @Override
    public int getNewListSize() {
      return newList.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
      return diffCallback.areItemsTheSame(
          oldList.get(oldItemPosition),
          newList.get(newItemPosition)
      );
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
      return diffCallback.areContentsTheSame(
          oldList.get(oldItemPosition),
          newList.get(newItemPosition)
      );
    }

    @Nullable
    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
      return diffCallback.getChangePayload(
          oldList.get(oldItemPosition),
          newList.get(newItemPosition)
      );
    }
  }
}