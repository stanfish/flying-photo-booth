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
package com.groundupworks.flyingphotobooth;

import android.app.Activity;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import com.groundupworks.flyingphotobooth.fragments.CaptureFragment;

/**
 * The launch {@link Activity}.
 * 
 * @author Benedict Lau
 */
public class LaunchActivity extends FragmentActivity {

    /**
     * Handler for the back pressed event.
     */
    private BackPressedHandler mBackPressedHandler = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);
        addFragment(CaptureFragment.newInstance(true), false);
    }

    @Override
    public void onBackPressed() {
        if (mBackPressedHandler == null || !mBackPressedHandler.isHandled()) {
            super.onBackPressed();
        }
    }

    //
    // Public methods.
    //

    /**
     * Adds a {@link Fragment} to the container.
     * 
     * @param fragment
     *            the new {@link Fragment} to add.
     * @param addToBackStack
     *            true to add transaction to back stack; false otherwise.
     */
    public void addFragment(Fragment fragment, boolean addToBackStack) {
        final FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.add(R.id.fragment_container, fragment);
        if (addToBackStack) {
            ft.addToBackStack(null);
        }
        ft.commit();
    }

    /**
     * Replaces a {@link Fragment} in the container.
     * 
     * @param fragment
     *            the new {@link Fragment} used to replace the current.
     * @param addToBackStack
     *            true to add transaction to back stack; false otherwise.
     * @param popPreviousState
     *            true to pop the previous state from the back stack; false otherwise.
     */
    public void replaceFragment(Fragment fragment, boolean addToBackStack, boolean popPreviousState) {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (popPreviousState) {
            fragmentManager.popBackStack();
        }

        final FragmentTransaction ft = fragmentManager.beginTransaction();
        ft.replace(R.id.fragment_container, fragment);
        if (addToBackStack) {
            ft.addToBackStack(null);
        }
        ft.commit();
    }

    /**
     * Sets a handler for the back pressed event.
     * 
     * @param handler
     *            the handler for the back pressed event. Pass null to clear.
     */
    public void setBackPressedHandler(BackPressedHandler handler) {
        mBackPressedHandler = handler;
    }

    //
    // Public interfaces.
    //

    /**
     * Handler interface for the back pressed event.
     */
    public interface BackPressedHandler {

        /**
         * @return true if back press event is handled; false otherwise.
         */
        boolean isHandled();
    }
}