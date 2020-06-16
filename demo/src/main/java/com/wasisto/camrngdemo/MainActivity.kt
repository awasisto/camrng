/*
 * Copyright (c) 2020 Andika Wasisto
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.wasisto.camrngdemo

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.wasisto.camrng.CameraInitializationFailedException
import com.wasisto.camrng.NoiseBasedCamRng
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 1
    }

    private var noiseBasedCamRng: NoiseBasedCamRng? = null

    private val compositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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

    private fun onPermissionGranted() {
        try {
            noiseBasedCamRng = NoiseBasedCamRng.newInstance(context = this)
            setupDiceRollButton()
            setupRandomDataStreamsViews()
            setupSamplePixelRawNoiseGraph()
        } catch (e: CameraInitializationFailedException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupDiceRollButton() {
        diceRollButton.setOnClickListener {
            compositeDisposable.add(
                noiseBasedCamRng!!.getInt(bound = 6)
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
    }

    private fun setupRandomDataStreamsViews() {
        compositeDisposable.add(
            noiseBasedCamRng!!.getBooleans()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    booleanTextView.text = it.toString()
                }
        )

        compositeDisposable.add(
            noiseBasedCamRng!!.getBytes()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    byteTextView.text = it.toString()
                }
        )

        compositeDisposable.add(
            noiseBasedCamRng!!.getShorts()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    shortTextView.text = it.toString()
                }
        )

        compositeDisposable.add(
            noiseBasedCamRng!!.getChars()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    charTextView.text = it.toString()
                }
        )

        compositeDisposable.add(
            noiseBasedCamRng!!.getInts()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    intTextView.text = it.toString()
                }
        )

        compositeDisposable.add(
            noiseBasedCamRng!!.getLongs()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    longTextView.text = it.toString()
                }
        )

        compositeDisposable.add(
            noiseBasedCamRng!!.getFloats()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    floatTextView.text = it.toString()
                }
        )

        compositeDisposable.add(
            noiseBasedCamRng!!.getDoubles()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    doubleTextView.text = it.toString()
                }
        )
    }

    private fun setupSamplePixelRawNoiseGraph() {
        val numberOfPixels = noiseBasedCamRng!!.pixels.size
        val samplePixelIndex = Random.nextInt(from = 0, until = numberOfPixels)
        val samplePixel = noiseBasedCamRng!!.pixels[samplePixelIndex]

        samplePixelXY.text = getString(R.string.sample_pixel_xy_placeholder, samplePixel.first, samplePixel.second)

        val dataSet = LineDataSet(mutableListOf(), null).apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.colorAccent)
            setDrawCircles(false)
            setDrawValues(false)
        }

        samplePixelRawNoiseGraph.apply {
            data = LineData(dataSet).apply {
                isHighlightEnabled = false
            }
            description = null
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTH_SIDED
            xAxis.axisMinimum = 0f
            xAxis.axisMaximum = 100f
            xAxis.setDrawLabels(false)
            xAxis.setDrawGridLines(false)
            axisLeft.setDrawLabels(false)
            axisLeft.setDrawGridLines(false)
            layoutParams.height = resources.displayMetrics.widthPixels / 2 - 48
        }

        compositeDisposable.add(
            noiseBasedCamRng!!.rawNoise
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    dataSet.addEntry(Entry(dataSet.entryCount.toFloat(), it[samplePixelIndex].toFloat()))

                    var minY = 255f
                    var maxY = 0f

                    for (entry in dataSet.values) {
                        minY = min(minY, entry.y)
                        maxY = max(maxY, entry.y)

                        if (samplePixelRawNoiseGraph.data.entryCount > 100) {
                            entry.x--
                        }
                    }

                    samplePixelRawNoiseGraph.axisLeft.axisMinimum = minY - 1
                    samplePixelRawNoiseGraph.axisLeft.axisMaximum = maxY + 1

                    if (samplePixelRawNoiseGraph.data.entryCount > 100) {
                        dataSet.removeFirst()
                    }

                    samplePixelRawNoiseGraph.notifyDataSetChanged()
                    samplePixelRawNoiseGraph.invalidate()
                }
        )
    }
}
