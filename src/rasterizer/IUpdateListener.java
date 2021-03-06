/*
 * Luke Diamond
 * 01/22/2018
 * Grade 11 Final Project
 * Mr. Patterson
 */

package rasterizer;

/**
 * Handles RasterPanel updates, passing delta time. Can be used as a lambda or
 * anonymous inner class.
 */
interface IUpdateListener {
	/**
	 * Called once per update of the rasterizer.
	 * @param delta The delta time of the previous frame.
	 */
    public void update(float delta);
}