/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.launcher3.util.viewcapture_analysis;

import static android.view.View.VISIBLE;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.app.viewcapture.data.ExportedData;
import com.android.app.viewcapture.data.FrameData;
import com.android.app.viewcapture.data.ViewNode;
import com.android.app.viewcapture.data.WindowData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility that analyzes ViewCapture data and finds anomalies such as views appearing or
 * disappearing without alpha-fading.
 */
public class ViewCaptureAnalyzer {
    private static final String SCRIM_VIEW_CLASS = "com.android.launcher3.views.ScrimView";

    /**
     * Detector of one kind of anomaly.
     */
    abstract static class AnomalyDetector {
        /**
         * Initializes fields of the node that are specific to the anomaly detected by this
         * detector.
         */
        abstract void initializeNode(@NonNull AnalysisNode info);

        /**
         * Detects anomalies by looking at the last occurrence of a view, and the current one.
         * null value means that the view. 'oldInfo' and 'newInfo' cannot be both null.
         * If an anomaly is detected, an exception will be thrown.
         *
         * @param oldInfo the view, as seen in the last frame that contained it in the view
         *                hierarchy before 'currentFrame'. 'null' means that the view is first seen
         *                in the 'currentFrame'.
         * @param newInfo the view in the view hierarchy of the 'currentFrame'. 'null' means that
         *                the view is not present in the 'currentFrame', but was present in earlier
         *                frames.
         * @param frameN  number of the current frame.
         */
        abstract void detectAnomalies(
                @Nullable AnalysisNode oldInfo, @Nullable AnalysisNode newInfo, int frameN);
    }

    // All detectors. They will be invoked in the order listed here.
    private static final Iterable<AnomalyDetector> ANOMALY_DETECTORS = Arrays.asList(
            new AlphaJumpDetector()
    );

    // A view from view capture data converted to a form that's convenient for detecting anomalies.
    static class AnalysisNode {
        public String className;
        public String resourceId;
        public AnalysisNode parent;

        // Window coordinates of the view.
        public float left;
        public float top;

        // Visible scale and alpha, build recursively from the ancestor list.
        public float scaleX;
        public float scaleY;
        public float alpha;

        public int frameN;
        public ViewNode viewCaptureNode;

        public boolean ignoreAlphaJumps;

        @Override
        public String toString() {
            return String.format("window coordinates: (%s, %s), class path from the root: %s",
                    left, top, diagPathFromRoot(this));
        }
    }

    /**
     * Scans a view capture record and throws an error if an anomaly is found.
     */
    public static void assertNoAnomalies(ExportedData viewCaptureData) {
        final int scrimClassIndex = viewCaptureData.getClassnameList().indexOf(SCRIM_VIEW_CLASS);

        final int windowDataCount = viewCaptureData.getWindowDataCount();
        for (int i = 0; i < windowDataCount; ++i) {
            analyzeWindowData(viewCaptureData, viewCaptureData.getWindowData(i), scrimClassIndex);
        }
    }

    private static void analyzeWindowData(ExportedData viewCaptureData, WindowData windowData,
            int scrimClassIndex) {
        // View hash code => Last seen node with this hash code.
        // The view is added when we analyze the first frame where it's visible.
        // After that, it gets updated for every frame where it's visible.
        // As we go though frames, if a view becomes invisible, it stays in the map.
        final Map<Integer, AnalysisNode> lastSeenNodes = new HashMap<>();

        for (int frameN = 0; frameN < windowData.getFrameDataCount(); ++frameN) {
            analyzeFrame(frameN, windowData.getFrameData(frameN), viewCaptureData, lastSeenNodes,
                    scrimClassIndex);
        }
    }

    private static void analyzeFrame(int frameN, FrameData frame, ExportedData viewCaptureData,
            Map<Integer, AnalysisNode> lastSeenNodes, int scrimClassIndex) {
        // Analyze the node tree starting from the root.
        analyzeView(
                frame.getNode(),
                /* parent = */ null,
                frameN,
                /* leftShift = */ 0,
                /* topShift = */ 0,
                viewCaptureData,
                lastSeenNodes,
                scrimClassIndex);

        // Analyze transitions when a view visible in the last frame become invisible in the
        // current one.
        for (AnalysisNode info : lastSeenNodes.values()) {
            if (info.frameN == frameN - 1) {
                if (!info.viewCaptureNode.getWillNotDraw()) {
                    ANOMALY_DETECTORS.forEach(
                            detector -> detector.detectAnomalies(
                                    /* oldInfo = */ info,
                                    /* newInfo = */ null,
                                    frameN));
                }
            }
        }
    }

    private static void analyzeView(ViewNode viewCaptureNode, AnalysisNode parent, int frameN,
            float leftShift, float topShift, ExportedData viewCaptureData,
            Map<Integer, AnalysisNode> lastSeenNodes, int scrimClassIndex) {
        // Skip analysis of invisible views
        final float parentAlpha = parent != null ? parent.alpha : 1;
        final float alpha = getVisibleAlpha(viewCaptureNode, parentAlpha);
        if (alpha <= 0.0) return;

        // Calculate analysis node parameters
        final int hashcode = viewCaptureNode.getHashcode();
        final int classIndex = viewCaptureNode.getClassnameIndex();

        final float parentScaleX = parent != null ? parent.scaleX : 1;
        final float parentScaleY = parent != null ? parent.scaleY : 1;
        final float scaleX = parentScaleX * viewCaptureNode.getScaleX();
        final float scaleY = parentScaleY * viewCaptureNode.getScaleY();

        final float left = leftShift
                + (viewCaptureNode.getLeft() + viewCaptureNode.getTranslationX()) * parentScaleX
                + viewCaptureNode.getWidth() * (parentScaleX - scaleX) / 2;
        final float top = topShift
                + (viewCaptureNode.getTop() + viewCaptureNode.getTranslationY()) * parentScaleY
                + viewCaptureNode.getHeight() * (parentScaleY - scaleY) / 2;

        // Initialize new analysis node
        final AnalysisNode newAnalysisNode = new AnalysisNode();
        newAnalysisNode.className = viewCaptureData.getClassname(classIndex);
        newAnalysisNode.resourceId = viewCaptureNode.getId();
        newAnalysisNode.parent = parent;
        newAnalysisNode.left = left;
        newAnalysisNode.top = top;
        newAnalysisNode.scaleX = scaleX;
        newAnalysisNode.scaleY = scaleY;
        newAnalysisNode.alpha = alpha;
        newAnalysisNode.frameN = frameN;
        newAnalysisNode.viewCaptureNode = viewCaptureNode;
        ANOMALY_DETECTORS.forEach(detector -> detector.initializeNode(newAnalysisNode));

        // Detect anomalies for the view
        final AnalysisNode oldAnalysisNode = lastSeenNodes.get(hashcode); // may be null
        if (frameN != 0 && !viewCaptureNode.getWillNotDraw()) {
            ANOMALY_DETECTORS.forEach(
                    detector -> detector.detectAnomalies(oldAnalysisNode, newAnalysisNode, frameN));
        }
        lastSeenNodes.put(hashcode, newAnalysisNode);

        // Enumerate children starting from the topmost one. Stop at ScrimView, if present.
        final float leftShiftForChildren = left - viewCaptureNode.getScrollX();
        final float topShiftForChildren = top - viewCaptureNode.getScrollY();
        for (int i = viewCaptureNode.getChildrenCount() - 1; i >= 0; --i) {
            final ViewNode child = viewCaptureNode.getChildren(i);

            // Don't analyze anything under scrim view because we don't know whether it's
            // transparent.
            if (child.getClassnameIndex() == scrimClassIndex) break;

            analyzeView(child, newAnalysisNode, frameN, leftShiftForChildren, topShiftForChildren,
                    viewCaptureData, lastSeenNodes,
                    scrimClassIndex);
        }
    }

    private static float getVisibleAlpha(ViewNode node, float parenVisibleAlpha) {
        return node.getVisibility() == VISIBLE
                ? parenVisibleAlpha * Math.max(0, Math.min(node.getAlpha(), 1))
                : 0f;
    }

    private static String classNameToSimpleName(String className) {
        return className.substring(className.lastIndexOf(".") + 1);
    }

    static String diagPathFromRoot(AnalysisNode nodeBox) {
        final StringBuilder path = new StringBuilder(diagPathElement(nodeBox));
        for (AnalysisNode ancestor = nodeBox.parent; ancestor != null; ancestor = ancestor.parent) {
            path.insert(0, diagPathElement(ancestor) + "|");
        }
        return path.toString();
    }

    private static String diagPathElement(AnalysisNode nodeBox) {
        final StringBuilder sb = new StringBuilder();
        sb.append(classNameToSimpleName(nodeBox.className));
        if (!"NO_ID".equals(nodeBox.resourceId)) sb.append(":" + nodeBox.resourceId);
        return sb.toString();
    }
}
