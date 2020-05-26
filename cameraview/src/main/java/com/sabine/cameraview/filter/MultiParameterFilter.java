package com.sabine.cameraview.filter;

/**
 * A special {@link Filter} that accepts a float parameter.
 *
 * The parameters will always be between 0F and 1F, so subclasses should
 * map this range to their internal range if needed.
 *
 * A standardized range is useful for different applications. For example:
 * - Filter parameters can be easily mapped to gestures since the range is fixed
 * - {@link BaseFilter} can use this setters and getters to make a filter copy
 */
public interface MultiParameterFilter extends TwoParameterFilter {

    /**
     * Sets the parameter.
     *
     * @param value parameter
     */
    void setParameterMulti(float[] value);

    /**
     * Returns the parameter.
     *
     * @return parameter
     */
    float[] getParameterMulti();
}
