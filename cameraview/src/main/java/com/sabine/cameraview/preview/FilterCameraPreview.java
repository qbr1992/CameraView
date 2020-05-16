package com.sabine.cameraview.preview;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.Filter;


/**
 * A preview that support GL filters defined through the {@link Filter} interface.
 *
 * The preview has the responsibility of calling {@link Filter#setSize(int, int)}
 * whenever the preview size changes and as soon as the filter is applied.
 */
public interface FilterCameraPreview {

    /**
     * Sets a new filter.
     * @param filter new filter
     *
     */
    public abstract void setFilter(@NonNull Filter filter);

    /**
     *
     * @param filterLevel
     */
    public abstract void setFilterLevel(@NonNull float filterLevel);

    /**
     *
     * @return filterLevel
     */
    public abstract float getFilterLevel();

    /**
     * Returns the currently used filter.
     * @return currently used filter
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract Filter getCurrentFilter();
}
