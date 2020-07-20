![CamRNG](https://i.imgur.com/3H8NW2B.png)
==========================================

An Android library project enabling quantum random number generation using device camera.

Description
-----------

CamRNG provides two types of generators that generate random numbers using different methods.
`NoiseBasedCamRng` generates random numbers by extracting image noises into random bits and
`ImageBasedCamRng` generates random numbers by hashing the images taken by the camera.

`NoiseBasedCamRng` can extract randomness from both shot noise and thermal noise. Shot noise or
quantum noise is the noise that dominates the lighter parts of an image. It is caused by the
variance of the number of photons detected by the photodiodes on the camera sensor per unit of time.
It can be used as the source of randomness by having a setup as shown in the following illustration.

![shot noise setup](https://i.imgur.com/9JEuNEC.png)

Thermal noise or Johnson-Nyquist noise is the noise that dominates the black parts of an image. It
is caused by the thermal agitation of the electrons in the camera sensor. It can be used as the
source of randomness by blocking the camera lens as shown in the following illustration.

![thermal noise setup](https://i.imgur.com/4xVSRR9.png)

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

    implementation 'com.wasisto.camrng:camrng:5.0.0'

Usage
-----

Visit [CamRNG Wiki](https://github.com/awasisto/camrng/wiki)

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