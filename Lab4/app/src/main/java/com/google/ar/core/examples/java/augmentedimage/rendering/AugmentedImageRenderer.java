/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.augmentedimage.rendering;

import android.content.Context;
import android.opengl.Matrix;

import com.google.ar.core.Anchor;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.Pose;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer.BlendMode;
import java.io.IOException;

/** Renders an augmented image. */
public class AugmentedImageRenderer {
  // Add a member variable to hold the maze model.
  private final ObjectRenderer mazeRenderer = new ObjectRenderer();
  // Render for Andy
  private final ObjectRenderer andyRenderer = new ObjectRenderer();
  private static final String TAG = "AugmentedImageRenderer";

  private static final float TINT_INTENSITY = 0.1f;
  private static final float TINT_ALPHA = 1.0f;
  private static final int[] TINT_COLORS_HEX = {
    0x000000, 0xF44336, 0xE91E63, 0x9C27B0, 0x673AB7, 0x3F51B5, 0x2196F3, 0x03A9F4, 0x00BCD4,
    0x009688, 0x4CAF50, 0x8BC34A, 0xCDDC39, 0xFFEB3B, 0xFFC107, 0xFF9800,
  };

  private final ObjectRenderer imageFrameUpperLeft = new ObjectRenderer();
  private final ObjectRenderer imageFrameUpperRight = new ObjectRenderer();
  private final ObjectRenderer imageFrameLowerLeft = new ObjectRenderer();
  private final ObjectRenderer imageFrameLowerRight = new ObjectRenderer();

  public AugmentedImageRenderer() {}

  public void createOnGlThread(Context context) throws IOException {

    imageFrameUpperLeft.createOnGlThread(
        context, "models/frame_upper_left.obj", "models/frame_base.png");
    imageFrameUpperLeft.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
    imageFrameUpperLeft.setBlendMode(BlendMode.AlphaBlending);

    imageFrameUpperRight.createOnGlThread(
        context, "models/frame_upper_right.obj", "models/frame_base.png");
    imageFrameUpperRight.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
    imageFrameUpperRight.setBlendMode(BlendMode.AlphaBlending);

    imageFrameLowerLeft.createOnGlThread(
        context, "models/frame_lower_left.obj", "models/frame_base.png");
    imageFrameLowerLeft.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
    imageFrameLowerLeft.setBlendMode(BlendMode.AlphaBlending);

    imageFrameLowerRight.createOnGlThread(
        context, "models/frame_lower_right.obj", "models/frame_base.png");
    imageFrameLowerRight.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
    imageFrameLowerRight.setBlendMode(BlendMode.AlphaBlending);
    // Initialize andyRenderer
//    andyRenderer.createOnGlThread(
//            context, "models/Banana.obj", "models/Banana_basecolor.png");
//    andyRenderer.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);

    andyRenderer.createOnGlThread(
            context, "models/nose.obj", "models/nose_fur.png");
    andyRenderer.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);

  }

  // Adjust size of detected image and render it on-screen
  public void draw(
          float[] viewMatrix,
          float[] projectionMatrix,
          AugmentedImage augmentedImage,
          Anchor centerAnchor,
          float[] colorCorrectionRgba,
          float angle) {
    float[] tintColor =
            convertHexToColor(TINT_COLORS_HEX[augmentedImage.getIndex() % TINT_COLORS_HEX.length]);

    final float noseEdgeSize = 20f; // Magic number of nose size
    final float maxImageEdgeSize = Math.max(augmentedImage.getExtentX(), augmentedImage.getExtentZ()); // Get largest detected image edge size

    Pose anchorPose = centerAnchor.getPose();

    float noseScaleFactor = maxImageEdgeSize / noseEdgeSize;
    float[] modelMatrix = new float[16];

    Pose andyModelLocalOffset = Pose.makeTranslation(
            0.0f,
            0.1f* noseScaleFactor,
            0.0f);
    anchorPose.compose(andyModelLocalOffset).toMatrix(modelMatrix, 0);
    Matrix.rotateM(modelMatrix, 0, 270, 1f, 0f, 0f);
    Matrix.rotateM(modelMatrix, 0, angle, 0f, 1f, 0f);
    //andyRenderer.updateModelMatrix(modelMatrix, 0.05f,0.05f,0.05f); // 0.05f is a Magic number to scale
    andyRenderer.updateModelMatrix(modelMatrix, 1f,1f,1f); // 0.05f is a Magic number to scale
    andyRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, tintColor);

  }
  private static float[] convertHexToColor(int colorHex) {
    // colorHex is in 0xRRGGBB format
    float red = ((colorHex & 0xFF0000) >> 16) / 255.0f * TINT_INTENSITY;
    float green = ((colorHex & 0x00FF00) >> 8) / 255.0f * TINT_INTENSITY;
    float blue = (colorHex & 0x0000FF) / 255.0f * TINT_INTENSITY;
    return new float[] {red, green, blue, TINT_ALPHA};
  }
}
