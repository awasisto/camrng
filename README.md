![CamRNG](https://i.imgur.com/3H8NW2B.png)
==========================================

An Android library project enabling quantum random number generation using device camera.

Download
--------

Download via Gradle:

    implementation 'com.wasisto.camrng:camrng:1.0.0'

Usage Example
-------------

```kotlin
class MyActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 1
    }

    private val compositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.my_activity)
    }

    override fun onStart() {
        super.onStart()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onPermissionGranted()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted()
            } else {
                finish()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun onPermissionGranted() {
        try {
            val camRng = NoiseBasedCamRng.newInstance(context = this)

            diceRollButton.setOnClickListener {
                compositeDisposable.add(
                    camRng.getInt(bound = 6)
                        .map {
                            it + 1
                        }
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { diceRollOutcome ->
                            diceRollOutcomeTextView.text = diceRollOutcome.toString()
                        }
                )
            }
        } catch (e: CameraInitializationFailedException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        NoiseBasedCamRng.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.dispose()
    }
}
```

License
-------

    Copyright (c) 2020 Andika Wasisto

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.