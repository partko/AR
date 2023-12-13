/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.opengl.Matrix
import android.util.Log
import androidx.annotation.ColorInt
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.asLiveData
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.MapView
import com.google.ar.core.codelabs.hellogeospatial.model.MainDB
import com.google.ar.core.codelabs.hellogeospatial.model.MarkEntity
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.maps.android.SphericalUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.logging.Handler
import javax.security.auth.callback.Callback


class HelloGeoRenderer(val activity: HelloGeoActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  val db = MainDB.getDb(this)

  var cameraPos = LatLng(0.0, 0.0)
  var curId = 0
  val anchorDists = booleanArrayOf(true, true, true, true, true)
  var isStartFrame = true
  var isCut = false

  private val EARTH_MARKER_COLOR: Int = Color.argb(255, 125, 125, 125)

  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "HelloGeoRenderer"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
  }

  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectTexture: Texture

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      // Virtual object to render (Geospatial Marker)
      virtualObjectTexture =
        Texture.createFromAsset(
          render,
          "models/spatial_marker_baked.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj");
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", virtualObjectTexture)

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
    start()
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {

    val session = session ?: return

    //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      }

    val camera = frame.camera

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      return
    }

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    //</editor-fold>

    // TODO: Obtain Geospatial information and display it on the map.
    val earth = session.earth
    if (earth?.trackingState == TrackingState.TRACKING) {
      // TODO: the Earth object may be used here.
      val cameraGeospatialPose = earth.cameraGeospatialPose
      activity.view.mapView?.updateMapPosition(
        latitude = cameraGeospatialPose.latitude,
        longitude = cameraGeospatialPose.longitude,
        heading = cameraGeospatialPose.heading
      )
      cameraPos = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
      activity.view.updateStatusText(earth, cameraGeospatialPose)

//      if (isStartFrame) {
//        isStartFrame = false
////        Thread{
////          start()
////        }.start()
//        CoroutineScope(Dispatchers.IO).launch {
//          start()
//        }
//      }
    }

    // Draw the placed anchor, if it exists.
//    earthAnchor?.let {
//      render.renderCompassAtAnchor(it)
//    }
    for (i in 0..4) {
      if (anchorDists[i])earthAnchorArray[i]?.let {
        render.renderCompassAtAnchor(it)
      }

    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)

  }

//  var earthAnchor: Anchor? = null
  val earthAnchorArray: Array<Anchor?> = arrayOfNulls(5)

  fun start() {
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      return
    }
    Log.d("debug", "start")
    earthAnchorArray[curId]?.detach()
    val latLngArray: Array<LatLng?> = arrayOfNulls(5)

    db.getDao().get5LastAnchor().asLiveData().observe(activity) { list ->
      Log.d("debug", "list = $list")
      list.forEach {
        earthAnchorArray[curId] = earth.createAnchor(
          it.latitude,
          it.longitude,
          it.altitude,
          it.qx, it.qy, it.qz, it.qw)

        if (isCut) {
          val dist = SphericalUtil.computeDistanceBetween(LatLng(it.latitude, it.longitude), cameraPos)
          anchorDists[curId] = dist <= 1000
        }
        when (curId) {
          0 -> {
            activity.view.mapView?.earthMarker0?.apply {
              position = LatLng(it.latitude, it.longitude)
              isVisible = if (isCut) {anchorDists[curId]} else {true}
              setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, it.angle)))
            }
          }
          1 -> {
            activity.view.mapView?.earthMarker1?.apply {
              position = LatLng(it.latitude, it.longitude)
              isVisible = if (isCut) {anchorDists[curId]} else {true}
              setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, it.angle)))
            }
          }
          2 -> {
            activity.view.mapView?.earthMarker2?.apply {
              position = LatLng(it.latitude, it.longitude)
              isVisible = if (isCut) {anchorDists[curId]} else {true}
              setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, it.angle)))
            }
          }
          3 -> {
            activity.view.mapView?.earthMarker3?.apply {
              position = LatLng(it.latitude, it.longitude)
              isVisible = if (isCut) {anchorDists[curId]} else {true}
              setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, it.angle)))
            }
          }
          4 -> {
            activity.view.mapView?.earthMarker4?.apply {
              position = LatLng(it.latitude, it.longitude)
              isVisible = if (isCut) {anchorDists[curId]} else {true}
              setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, it.angle)))
            }
          }
        }
        //latLngArray[curId] = LatLng(it.latitude, it.longitude)
        Log.d("debug", "curId = $curId latitude = ${it.latitude} longitude = ${it.longitude}")
        ++curId
        if (curId == 5) curId = 0
      }
    }
    Log.d("debug", "latLngArray[0] = ${latLngArray[0]?.latitude} ${latLngArray[0]?.longitude}")

    //Log.d("debug", "$curId")

  }

  private fun createColoredMarkerBitmap(@ColorInt color: Int, angle: Float): Bitmap {
    val opt = BitmapFactory.Options()
    opt.inMutable = true
    val navigationIcon =
      BitmapFactory.decodeResource(activity.resources, R.drawable.ic_navigation_white_48dp, opt).rotate(angle)
    val p = Paint()
    p.colorFilter = LightingColorFilter(color,  /* add= */1)
    val canvas = Canvas(navigationIcon)
    canvas.drawBitmap(navigationIcon,  /* left= */0f,  /* top= */0f, p)
    return navigationIcon
  }

  fun Bitmap.rotate(degrees: Float): Bitmap {
    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
  }

  fun onLongMapClick() {
    onMapClick(cameraPos!!, 0f)
  }

  fun onMapClick(latLng: LatLng, angle: Float) {
    if (isStartFrame) {
        isStartFrame = false
        start()
        return
    }
    // TODO: place an anchor at the given position.
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      return
    }

//    earthAnchor?.detach()
    earthAnchorArray[curId]?.detach()

    // Place the earth anchor at the same altitude as that of the camera to make it easier to view.
    val altitude = earth.cameraGeospatialPose.altitude - 1
    // The rotation quaternion of the anchor in the East-Up-South (EUS) coordinate system.
    val qx = 0f
    val qy = 0f
    val qz = 0f
    val qw = 1f

//    earthAnchor = earth.createAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw)
//
//    activity.view.mapView?.earthMarker?.apply {
//      position = latLng
//      isVisible = true
//    }

    Thread {
      db.getDao().insert(
        MarkEntity(
          null,
          latLng.latitude,
          latLng.longitude,
          altitude,
          qx, qy, qz, qw,
          angle
        )
      )
    }.start()
    db.getDao().get5LastAnchor().asLiveData().observe(activity) { list ->
      //Log.d("debug", "list = $list")
      list.forEach {
        earthAnchorArray[curId] = earth.createAnchor(
          it.latitude,
          it.longitude,
          it.altitude,
          it.qx, it.qy, it.qz, it.qw)
        ++curId
        if (curId == 5) curId = 0
      }
    }
    val dist = SphericalUtil.computeDistanceBetween(LatLng(latLng.latitude, latLng.longitude), cameraPos)
    anchorDists[curId] = dist <= 1000
    Log.d("debug", "$curId")

    //if (anchorDists[curId]){
      when (curId) {
        0 -> {
          activity.view.mapView?.earthMarker0?.apply {
            position = latLng
            isVisible = true
            setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, angle)))
          }
        }
        1 -> {
          activity.view.mapView?.earthMarker1?.apply {
            position = latLng
            isVisible = true
            setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, angle)))
          }
        }
        2 -> {
          activity.view.mapView?.earthMarker2?.apply {
            position = latLng
            isVisible = true
            setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, angle)))
          }
        }
        3 -> {
          activity.view.mapView?.earthMarker3?.apply {
            position = latLng
            isVisible = true
            setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, angle)))
          }
        }
        4 -> {
          activity.view.mapView?.earthMarker4?.apply {
            position = latLng
            isVisible = true
            setIcon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(EARTH_MARKER_COLOR, angle)))
          }
        }
      }
    //}
    ++curId
    if (curId == 5) curId = 0
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor) {
    // Get the current pose of the Anchor in world space. The Anchor pose is updated
    // during calls to session.update() as ARCore refines its estimate of the world.
    anchor.pose.toMatrix(modelMatrix, 0)

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Update shader properties and draw
    virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}
