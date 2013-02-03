/*
 * Copyright (C) 2012 Benedict Lau
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
package com.groundupworks.flyingphotobooth.views;

import java.io.IOException;
import java.util.List;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import com.groundupworks.flyingphotobooth.helpers.CameraHelper;
import com.groundupworks.flyingphotobooth.helpers.ImageHelper;

/**
 * Layout containing a centered preview resized to fit inside the layout while preserving the aspect ratio. The view
 * applies a mask to reveal only the center square region of the preview. The view also configures the camera, set
 * through {@link #setCamera(Camera, int)}, to use the optimal supported preview size.
 * 
 * @author Benedict Lau
 */
public class CenteredPreview extends ViewGroup implements SurfaceHolder.Callback {

    private static final String TAG = CenteredPreview.class.getSimpleName();

    /**
     * The target aspect ratio used to select the optimal preview size.
     */
    private static final double ASPECT_RATIO_TARGET = 1.0d;

    /**
     * The preview surface dimensions must be an integer multiple of this factor, otherwise we may get a blank line at
     * one of the edges.
     */
    private static final int PREVIEW_SIZE_BLOCK = 8;

    /**
     * Default color of the crop masks.
     */
    private static final int DEFAULT_MASK_COLOR = Color.BLACK;

    /**
     * Flag to indicate whether the surface has been created.
     */
    private boolean mSurfaceCreated = false;

    /**
     * The preview display orientation. Valid values are {@link #PREVIEW_DISPLAY_ORIENTATION_0},
     * {@link #PREVIEW_DISPLAY_ORIENTATION_90}, {@link #PREVIEW_DISPLAY_ORIENTATION_180}, and
     * {@link #PREVIEW_DISPLAY_ORIENTATION_270}.
     */
    private int mPreviewDisplayOrientation = CameraHelper.CAMERA_SCREEN_ORIENTATION_0;

    /**
     * The list of preview sizes supported by the camera.
     */
    private List<Size> mSupportedPreviewSizes = null;

    /**
     * The selected preview size.
     */
    private Size mPreviewSize = null;

    /**
     * The camera to fill the preview surface.
     */
    private Camera mCamera = null;

    /**
     * The surface to draw the camera preview.
     */
    private SurfaceView mSurfaceView = null;

    //
    // Masks to indicate crop region.
    //

    private View mTopOrLeftMask;

    private View mBottomOrRightMask;

    /**
     * Constructor.
     * 
     * @param context
     *            the {@link Context} the view is running in, through which it can access the current theme, resources,
     *            etc.
     */
    public CenteredPreview(Context context) {
        super(context);
        init(context);
    }

    /**
     * Constructor.
     * 
     * @param context
     *            the {@link Context} the view is running in, through which it can access the current theme, resources,
     *            etc.
     * @param attrs
     *            the attributes of the XML tag that is inflating the view.
     */
    public CenteredPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    /**
     * Constructor.
     * 
     * @param context
     *            the {@link Context} the view is running in, through which it can access the current theme, resources,
     *            etc.
     * @param attrs
     *            the attributes of the XML tag that is inflating the view.
     * @param defStyle
     *            the default style to apply to this view. If 0, no style will be applied (beyond what is included in
     *            the theme). This may either be an attribute resource, whose value will be retrieved from the current
     *            theme, or an explicit style resource.
     */
    public CenteredPreview(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Ignore child measurements and calculate layout size.
        final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);

        // Set layout size.
        setMeasuredDimension(width, height);

        if (mCamera != null && mSupportedPreviewSizes != null) {
            // Calculate the optimal supported preview size based on the layout size.
            if (mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_0
                    || mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_180) {
                mPreviewSize = CameraHelper.getOptimalPreviewSize(mSupportedPreviewSizes, width, height,
                        ASPECT_RATIO_TARGET);
            } else if (mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_90
                    || mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_270) {
                mPreviewSize = CameraHelper.getOptimalPreviewSize(mSupportedPreviewSizes, height, width,
                        ASPECT_RATIO_TARGET);
            } else {
                throw new IllegalArgumentException("Invalid value specified for preview display orientation");
            }

            // Configure the camera to output in the optimal calculated size.
            Camera.Parameters params = mCamera.getParameters();
            params.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
            mCamera.setParameters(params);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Calculate the maximum dimensions that the preview can take.
        final int parentWidth = right - left;
        final int parentHeight = bottom - top;

        // Calculate the preview dimensions after any applicable rotations.
        int previewWidth = parentWidth;
        int previewHeight = parentHeight;
        if (mPreviewSize != null) {
            if (mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_0
                    || mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_180) {
                previewWidth = mPreviewSize.width;
                previewHeight = mPreviewSize.height;
            } else if (mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_90
                    || mPreviewDisplayOrientation == CameraHelper.CAMERA_SCREEN_ORIENTATION_270) {
                previewWidth = mPreviewSize.height;
                previewHeight = mPreviewSize.width;
            } else {
                throw new IllegalArgumentException("Invalid value specified for preview display orientation");
            }
        }

        /*
         * Layout children views.
         */
        Point previewSurfaceSize = ImageHelper.getAspectFitSize(parentWidth, parentHeight, previewWidth, previewHeight);
        int previewSurfaceWidth = previewSurfaceSize.x;
        int previewSurfaceHeight = previewSurfaceSize.y;

        // Ensure preview dimensions are multiples of a preview size factor.
        previewSurfaceWidth -= previewSurfaceWidth % PREVIEW_SIZE_BLOCK;
        previewSurfaceHeight -= previewSurfaceHeight % PREVIEW_SIZE_BLOCK;

        // Center the preview surface within the parent container.
        mSurfaceView.layout((parentWidth - previewSurfaceWidth) / 2, (parentHeight - previewSurfaceHeight) / 2,
                (parentWidth + previewSurfaceWidth) / 2, (parentHeight + previewSurfaceHeight) / 2);

        // Set masks to show only a square region at the center.
        if (previewSurfaceWidth > previewSurfaceHeight) {
            // Mask left and right edges.
            mTopOrLeftMask.layout(0, 0, (parentWidth - previewSurfaceHeight) / 2, parentHeight);
            mBottomOrRightMask.layout((parentWidth + previewSurfaceHeight) / 2, 0, parentWidth, parentHeight);
        } else if (previewSurfaceWidth < previewSurfaceHeight) {
            // Mask top and bottom edges.
            mTopOrLeftMask.layout(0, 0, parentWidth, (parentHeight - previewSurfaceWidth) / 2);
            mBottomOrRightMask.layout(0, (parentHeight + previewSurfaceWidth) / 2, parentWidth, parentHeight);
        } else {
            // Do not layout mask.
        }
    }

    //
    // SurfaceHolder.Callback implementation.
    //

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mCamera != null) {
            try {
                mCamera.setPreviewDisplay(holder);

                if (mPreviewSize == null) {
                    // The preview size will be set when the view is measured.
                    requestLayout();
                }
            } catch (IOException exception) {
                Log.e(TAG, "IOException caused by setPreviewDisplay()", exception);
            }
        }

        mSurfaceCreated = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        start();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mCamera != null) {
            mCamera.stopPreview();
        }
    }

    //
    // Private methods.
    //

    /**
     * Initializes the layout by adding a preview surface and the masking views to indicate a crop region.
     * 
     * @param context
     *            the Context.
     */
    private void init(Context context) {
        /*
         * Init preview surface.
         */
        mSurfaceView = new SurfaceView(context);
        addView(mSurfaceView);

        // Set callbacks to get notifications about surface events.
        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        /*
         * Init masks.
         */
        Drawable maskColor = getBackground();
        if (maskColor == null) {
            maskColor = new ColorDrawable(DEFAULT_MASK_COLOR);
        }

        mTopOrLeftMask = new View(context);
        mTopOrLeftMask.setBackgroundDrawable(maskColor);
        addView(mTopOrLeftMask);

        mBottomOrRightMask = new View(context);
        mBottomOrRightMask.setBackgroundDrawable(maskColor);
        addView(mBottomOrRightMask);
    }

    //
    // Public methods.
    //

    /**
     * Sets the camera to use. The client is responsible for locking the camera, and clearing the camera reference by
     * passing null before releasing.
     * 
     * @param camera
     *            the Camera to use for preview.
     * @param previewDisplayOrientation
     *            the display orientation of the preview. Valid values are {@link #PREVIEW_DISPLAY_ORIENTATION_0},
     *            {@link #PREVIEW_DISPLAY_ORIENTATION_90}, {@link #PREVIEW_DISPLAY_ORIENTATION_180}, and
     *            {@link #PREVIEW_DISPLAY_ORIENTATION_270}.
     */
    public void setCamera(Camera camera, int previewDisplayOrientation) {
        mCamera = camera;
        mPreviewDisplayOrientation = previewDisplayOrientation;

        if (camera != null) {
            mSupportedPreviewSizes = camera.getParameters().getSupportedPreviewSizes();

            if (mSurfaceCreated) {
                // Invalidate the layout since camera has changed.
                requestLayout();
            }
        }
    }

    /**
     * Starts the preview if both camera and surface are ready.
     */
    public void start() {
        if (mCamera != null && mSurfaceCreated) {
            mCamera.startPreview();
        }
    }
}
