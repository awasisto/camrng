![CamRNG](https://i.imgur.com/3H8NW2B.png)
==========================================

An Android library project enabling quantum random number generation using device camera.

Setup
-----

CamRNG provides two types of generators that generate random numbers using different methods.
`NoiseBasedCamRng` generates random numbers by extracting image noises into random bits and
`ImageBasedCamRng` generates random numbers by hashing the images taken by the camera.

`NoiseBasedCamRng` can extract randomness from both shot noise and thermal noise. Shot noise or
quantum noise is the noise that dominates the lighter parts of an image. It is caused by the
variance of the number of photons detected by the photodiodes on the camera sensor per unit of time.
It can be used as the source of randomness by having a setup as shown in the following illustration.

![shot noise setup](https://i.imgur.com/5ZjEeBl.png)

Thermal noise or Johnson-Nyquist noise is the noise that dominates the black parts of an image. It
is caused by the thermal agitation of the electrons in the camera sensor. It can be used as the
source of randomness by blocking the camera lens as shown in the following illustration.

![thermal noise setup](https://i.imgur.com/98fitAW.png)

`ImageBasedCamRng` generates random numbers by continuously taking images and applying SHA-512 to
them. Unlike `NoiseBasedCamRng` that needs additional setup to work, `ImageBasedCamRng` can generate
high-quality randomness by pointing the camera at anything at the cost of a lower bitrate.

NIST SP 800-22 Tests Results
----------------------------

![NoiseBasedCamRng with shot noise tests results](https://i.imgur.com/Qx78Uud.png)

![NoiseBasedCamRng with thermal noise tests results](https://i.imgur.com/q2rCA9x.png)

![ImageBasedCamRng tests results](https://i.imgur.com/sfC8YJH.png)

Download
--------

Download via Gradle:

    implementation 'com.wasisto.camrng:camrng:1.0.1'

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
            if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
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