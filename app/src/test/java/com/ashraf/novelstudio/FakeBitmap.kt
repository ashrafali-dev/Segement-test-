package com.ashraf.novelstudio.fake

/**
 * Stand-in for android.graphics.Bitmap in JVM unit tests. A test in this module can shadow an android.* type by
 * declaring `typealias Bitmap = com.ashraf.novelstudio.fake.Bitmap` in its own package, which lets code under
 * test run on the JVM without Robolectric (see UserRegionOcrEngineTest).
 */
class Bitmap(val width: Int, val height: Int) {
    var recycled = false
        private set

    /** Ids of the bitmap this one was produced from (scale/contrast), for shape assertions in tests. */
    var derivedFrom: Int = 0

    fun recycle() { recycled = true }
}
