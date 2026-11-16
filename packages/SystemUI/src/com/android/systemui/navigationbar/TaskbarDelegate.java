/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.StatusBarManager.NAVBAR_BACK_DISMISS_IME;
import static android.app.StatusBarManager.NAVBAR_IME_SWITCHER_BUTTON_VISIBLE;
import static android.app.StatusBarManager.NAVBAR_IME_VISIBLE;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.systemui.navigationbar.NavBarHelper.transitionMode;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BACK_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BACK_DISMISS_IME;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_BUTTON_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.window.flags.Flags.enableSysDecorsCallbacksViaWm;

import android.app.StatusBarManager;
import android.app.StatusBarManager.NavbarFlags;
import android.app.StatusBarManager.WindowVisibleState;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService.BackDispositionMode;
import android.inputmethodservice.InputMethodService.ImeWindowVisibility;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import android.window.WindowContext;
import android.widget.FrameLayout;

import com.android.internal.policy.PhoneWindow;
import com.android.app.displaylib.DisplayDecorationListener;
import com.android.app.displaylib.DisplaysWithDecorationsRepositoryCompat;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.Dumpable;
import com.android.systemui.LauncherProxyService;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.navigationbar.spectrum.SpectrumManager;
import com.android.systemui.navigationbar.spectrum.SpectrumView;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.statusbar.phone.BarTransitions;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import kotlinx.coroutines.CoroutineDispatcher;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;

/** */
@SysUISingleton
public class TaskbarDelegate implements CommandQueue.Callbacks,
        LauncherProxyService.LauncherProxyListener, NavigationModeController.ModeChangedListener,
        Dumpable {
    private static final String TAG = TaskbarDelegate.class.getSimpleName();

    private EdgeBackGestureHandler mEdgeBackGestureHandler;
    private final LightBarTransitionsController.Factory mLightBarTransitionsControllerFactory;
    private boolean mInitialized;
    private CommandQueue mCommandQueue;
    private LauncherProxyService mLauncherProxyService;
    private NavBarHelper mNavBarHelper;
    private NavigationModeController mNavigationModeController;
    private SysUiState mSysUiState;
    private AutoHideController mAutoHideController;
    private LightBarController mLightBarController;
    private LightBarTransitionsController mLightBarTransitionsController;
    private TaskStackChangeListeners mTaskStackChangeListeners;
    private Optional<Pip> mPipOptional;
    private int mDefaultDisplayId;
    @NavbarFlags
    private int mNavbarFlags;
    private final NavBarHelper.NavbarTaskbarStateUpdater mNavbarTaskbarStateUpdater =
            new NavBarHelper.NavbarTaskbarStateUpdater() {
                @Override
                public void updateAccessibilityServicesState() {
                    updateSysuiFlags();
                }

                @Override
                public void updateAssistantAvailable(boolean available,
                        boolean longPressHomeEnabled) {
                    updateAssistantAvailability(available, longPressHomeEnabled);
                }

                @Override
                public void updateWallpaperVisibility(boolean visible, int displayId) {
                    updateWallpaperVisible(displayId, visible);
                }
            };
    private int mDisabledFlags;
    private @WindowVisibleState int mTaskBarWindowState = WINDOW_STATE_SHOWING;
    private SpectrumManager mSpectrumManager;
    private SpectrumView mSpectrumView;
    private WindowManager mSpectrumWindowManager;
    private Window mSpectrumWindow;
    private FrameLayout mSpectrumHost;
    private boolean mSpectrumWindowAdded;
    private NotificationShadeWindowController mNotificationShadeWindowController;
    private SpectrumView mLockscreenSpectrumView;
    private boolean mLockscreenSpectrumAdded;
    private boolean mKeyguardShowing;

    /** Refreshes the spectrum overlay placement when the display rotates. */
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == mDefaultDisplayId) {
                        updateSpectrumState();
                    }
                }
            };

    private @TransitionMode int mTransitionMode;
    private @Appearance int mAppearance;
    private @Behavior int mBehavior;
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private Context mWindowContext;
    private ScreenPinningNotify mScreenPinningNotify;
    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onLockTaskModeChanged(int mode) {
            mSysUiState.setFlag(SYSUI_STATE_SCREEN_PINNING, mode == LOCK_TASK_MODE_PINNED)
                    .commitUpdate(mDefaultDisplayId);
        }
    };

    private int mNavigationMode = -1;
    private final Consumer<Rect> mPipListener;

    /**
     * Tracks the system calls for when taskbar should transiently show or hide so we can return
     * this value in {@link AutoHideUiElement#isVisible()} below.
     *
     * This also gets set by {@link #onTaskbarAutohideSuspend(boolean)} to force show the transient
     * taskbar if launcher has requested to suspend auto-hide behavior.
     */
    private boolean mTaskbarTransientShowing;
    private final AutoHideUiElement mAutoHideUiElement = new AutoHideUiElement() {
        @Override
        public void synchronizeState() {
            Display[] displays = mDisplayTracker.getAllDisplays();
            for (Display display : displays) {
                checkNavBarModes(display.getDisplayId());
            }
        }

        @Override
        public boolean isVisible() {
            return mTaskbarTransientShowing;
        }

        @Override
        public void hide() {
        }
    };

    private BackAnimation mBackAnimation;

    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final StatusBarStateController mStatusBarStateController;
    private DisplayTracker mDisplayTracker;
    private final Handler mBgHandler;

    private final DisplayDecorationListener mDisplayDecorationListener =
            new DisplayDecorationListener() {
                @Override
                public void onDisplayRemoved(int displayId) {
                    mEdgeBackGestureHandler.onDisplayRemoveSystemDecorations(displayId);
                    if (mLauncherProxyService.getProxy() == null) {
                        return;
                    }

                    try {
                        mLauncherProxyService.getProxy().onDisplayRemoved(displayId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "onDisplayRemoved() failed", e);
                    }
                }

                @Override
                public void onDisplayRemoveSystemDecorations(int displayId) {
                    mEdgeBackGestureHandler.onDisplayRemoveSystemDecorations(displayId);
                    if (mLauncherProxyService.getProxy() == null) {
                        return;
                    }

                    try {
                        mLauncherProxyService.getProxy()
                                .onDisplayRemoveSystemDecorations(displayId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "onDisplaySystemDecorationsRemoved() failed", e);
                    }
                }

                @Override
                public void onDisplayAddSystemDecorations(int displayId) {
                    mEdgeBackGestureHandler.onDisplayAddSystemDecorations(displayId);
                    if (mLauncherProxyService.getProxy() == null) {
                        return;
                    }

                    try {
                        mLauncherProxyService.getProxy().onDisplayAddSystemDecorations(displayId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "onDisplayAddSystemDecorations() failed", e);
                    }
                }};

    @Inject
    public TaskbarDelegate(Context context,
            LightBarTransitionsController.Factory lightBarTransitionsControllerFactory,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            StatusBarStateController statusBarStateController,
            NotificationShadeWindowController notificationShadeWindowController,
            @Background Handler bgHandler) {
        mLightBarTransitionsControllerFactory = lightBarTransitionsControllerFactory;

        mContext = context;
        mBgHandler = bgHandler;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mPipListener = (bounds) -> {
            mEdgeBackGestureHandler.setPipStashExclusionBounds(bounds);
        };
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mStatusBarKeyguardViewManager.setTaskbarDelegate(this);
        mNotificationShadeWindowController = notificationShadeWindowController;
        mStatusBarStateController = statusBarStateController;
    }

    public void setDependencies(CommandQueue commandQueue,
            LauncherProxyService launcherProxyService,
            NavBarHelper navBarHelper,
            NavigationModeController navigationModeController,
            SysUiState sysUiState, DumpManager dumpManager,
            AutoHideController autoHideController,
            LightBarController lightBarController,
            Optional<Pip> pipOptional,
            BackAnimation backAnimation,
            TaskStackChangeListeners taskStackChangeListeners,
            DisplayTracker displayTracker,
            DisplaysWithDecorationsRepositoryCompat displaysWithDecorationsRepositoryCompat,
            @Main CoroutineDispatcher mainCoroutineDispatcher) {
        // TODO: adding this in the ctor results in a dagger dependency cycle :(
        mCommandQueue = commandQueue;
        mLauncherProxyService = launcherProxyService;
        mNavBarHelper = navBarHelper;
        mNavigationModeController = navigationModeController;
        mSysUiState = sysUiState;
        dumpManager.registerDumpable(this);
        mAutoHideController = autoHideController;
        mLightBarController = lightBarController;
        mPipOptional = pipOptional;
        mBackAnimation = backAnimation;
        mLightBarTransitionsController = createLightBarTransitionsController();
        mTaskStackChangeListeners = taskStackChangeListeners;
        mEdgeBackGestureHandler = navBarHelper.getEdgeBackGestureHandler();
        mDisplayTracker = displayTracker;
        if (enableSysDecorsCallbacksViaWm()) {
            displaysWithDecorationsRepositoryCompat.registerDisplayDecorationListener(
                    mDisplayDecorationListener, mainCoroutineDispatcher);
        }
    }

    @Override
    public void onDisplayAddSystemDecorations(int displayId) {
        mDisplayDecorationListener.onDisplayAddSystemDecorations(displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mDisplayDecorationListener.onDisplayRemoved(displayId);
    }

    @Override
    public void onDisplayRemoveSystemDecorations(int displayId) {
        mDisplayDecorationListener.onDisplayRemoveSystemDecorations(displayId);
    }

    // Separated into a method to keep setDependencies() clean/readable.
    private LightBarTransitionsController createLightBarTransitionsController() {

        LightBarTransitionsController controller =  mLightBarTransitionsControllerFactory.create(
                new LightBarTransitionsController.DarkIntensityApplier() {
                    @Override
                    public void applyDarkIntensity(float darkIntensity) {
                        mBgHandler.post(() -> {
                            mLauncherProxyService.onNavButtonsDarkIntensityChanged(darkIntensity);
                        });
                    }

                    @Override
                    public int getTintAnimationDuration() {
                        return LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION;
                    }
                });

        return controller;
    }

    public void init(int displayId) {
        Trace.beginSection("TaskbarDelegate#init");
        try {
            if (mInitialized) {
                return;
            }
            mDefaultDisplayId = displayId;
            parseCurrentSysuiState();
            mCommandQueue.addCallback(this);
            mLauncherProxyService.addCallback(this);
            onNavigationModeChanged(mNavigationModeController.addListener(this));
            mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
            // Initialize component callback
            Display display = mDisplayManager.getDisplay(displayId);
            mWindowContext = mContext.createWindowContext(display, TYPE_APPLICATION, null);
            mScreenPinningNotify = new ScreenPinningNotify(mWindowContext);
            mStatusBarStateController.addCallback(mStatusBarStateListener);
            mKeyguardShowing = isKeyguardShowing(mStatusBarStateController.getState());
            // Set initial state for any listeners
            updateSysuiFlags();
            mAutoHideController.setNavigationBar(mAutoHideUiElement);
            mLightBarController.setNavigationBar(mLightBarTransitionsController);
            mPipOptional.ifPresent(this::addPipExclusionBoundsChangeListener);
            mEdgeBackGestureHandler.setBackAnimation(mBackAnimation);
            mTaskStackChangeListeners.registerTaskStackListener(mTaskStackListener);
            // Refresh spectrum placement when the display rotates.
            mDisplayManager.registerDisplayListener(mDisplayListener,
                    new Handler(Looper.getMainLooper()));
            mInitialized = true;
            updateSpectrumState();
        } finally {
            Trace.endSection();
        }
    }

    public void destroy() {
        if (!mInitialized) {
            return;
        }
        mCommandQueue.removeCallback(this);
        mLauncherProxyService.removeCallback(this);
        mNavigationModeController.removeListener(this);
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mScreenPinningNotify = null;
        mWindowContext = null;
        mAutoHideController.setNavigationBar(null);
        mLightBarTransitionsController.destroy();
        mLightBarController.setNavigationBar(null);
        mPipOptional.ifPresent(this::removePipExclusionBoundsChangeListener);
        mTaskStackChangeListeners.unregisterTaskStackListener(mTaskStackListener);
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        stopLockscreenSpectrumLocked();
        stopSpectrum();
        mInitialized = false;
    }

    /**
     * Starts or stops the loopback audio spectrum capture based on whether the task bar is
     * currently hosting the navigation bar and visible.
     *
     * On devices where the navigation bar is provided by the task bar instead of the classic
     * {@code NavigationBar}, the start of the spectrum capture has to be driven from this path;
     * the classic nav bar implementation lives in
     * {@code com.android.systemui.navigationbar.views.NavigationBarInflaterView}.
     */
    private void updateSpectrumState() {
        try {
            Log.d(TAG, "updateSpectrumState: keyguardShowing=" + mKeyguardShowing
                    + " visible=" + isWindowVisible()
                    + " transientShowing=" + mTaskbarTransientShowing
                    + " mSpectrumWindowAdded=" + mSpectrumWindowAdded
                    + " mLockscreenSpectrumAdded=" + mLockscreenSpectrumAdded);
            if (mKeyguardShowing) {
                // While the keyguard is showing the spectrum lives on the lock screen, behind the
                // keyguard content. The navbar variant must stay off so there is never two at once.
                stopNavbarSpectrumLocked();
                startLockscreenSpectrum();
            } else {
                stopLockscreenSpectrumLocked();
                if (isWindowVisible() || mTaskbarTransientShowing) {
                    startNavbarSpectrum();
                } else {
                    stopNavbarSpectrumLocked();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updateSpectrumState: exception: " + e.getMessage(), e);
        }
    }

    private void ensureSpectrumManagerCreated() {
        if (mSpectrumManager == null) {
            mSpectrumManager = new SpectrumManager(mContext);
        }
    }

    private void attachSpectrumHostIfNeeded() {
        Log.d(TAG, "attachSpectrumHostIfNeeded: mSpectrumWindowAdded=" + mSpectrumWindowAdded
                + " mSpectrumHost=" + mSpectrumHost);
        // A SpectrumView can only have one parent. Detach it from any previous host before
        // re-adding, otherwise addView throws "View already has a parent" and the window is
        // never shown (the exception is swallowed below).
        if (mSpectrumView != null && mSpectrumView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mSpectrumView.getParent()).removeView(mSpectrumView);
        }

        // Create the host FrameLayout once; on re-attach (e.g. after rotation) we reuse it and
        // just refresh the window manager layout params.
        if (mSpectrumHost == null) {
            FrameLayout host = new FrameLayout(mContext);
            //host.setBackgroundColor(0xFFFF0000); // Test: red rectangle
            FrameLayout.LayoutParams hostParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mSpectrumHost = host;
        }
        // (Re)attach the SpectrumView to the host on every attach so it survives rotation.
        if (mSpectrumView.getParent() != mSpectrumHost) {
            mSpectrumHost.addView(mSpectrumView,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mSpectrumView.setVisibility(View.VISIBLE);

        if (mSpectrumWindowManager == null) {
            // Use SystemUI's own window manager (the same window the task bar uses), not a
            // window-context one, so system window types are accepted.
            mSpectrumWindowManager = mContext.getSystemService(WindowManager.class);
        }

        final int spectrumBarSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);

        // Place the overlay to mirror the task bar window placement. The task bar lives on the
        // display bottom in portrait and moves to the start/end side on landscape only for
        // phone-button navigation; gesture navigation keeps a bottom-based task bar/nav pill, so
        // the spectrum overlay should stay at the bottom there. Mirrored from
        // TaskbarActivityContext.populatePhoneButtonNavModeWindowLayoutParams / setTaskbarWindowSize.
        // Re-applied on every attach so rotation (signaled via mDisplayListener -> updateSpectrumState)
        // actually moves the overlay instead of leaving it pinned to the boot-time edge.
        final int rotation;
        final Display display = mDisplayManager.getDisplay(mDefaultDisplayId);
        rotation = display == null ? Surface.ROTATION_0 : display.getRotation();

        int gravity;
        int width;
        int height;
        if (QuickStepContract.isGesturalMode(mNavigationMode)) {
            // Gesture navigation: task bar / nav pill is bottom-based, keep the overlay at the
            // bottom edge.
            gravity = Gravity.BOTTOM;
            width = ViewGroup.LayoutParams.MATCH_PARENT;
            height = spectrumBarSize;
            mSpectrumView.setFlip(false);
        } else {
            // Legacy (3-button) navigation: bottom in portrait, start/end side in landscape.
            switch (rotation) {
                case Surface.ROTATION_90:
                    // Landscape: nav bar on the right edge.
                    gravity = Gravity.END;
                    width = spectrumBarSize;
                    height = ViewGroup.LayoutParams.MATCH_PARENT;
                    mSpectrumView.setFlip(true);
                    break;
                case Surface.ROTATION_270:
                    // Reverse landscape: nav bar on the left edge.
                    gravity = Gravity.START;
                    width = spectrumBarSize;
                    height = ViewGroup.LayoutParams.MATCH_PARENT;
                    mSpectrumView.setFlip(false);
                    break;
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                default:
                    // Portrait: nav bar at the bottom edge.
                    gravity = Gravity.BOTTOM;
                    width = ViewGroup.LayoutParams.MATCH_PARENT;
                    height = spectrumBarSize;
                    mSpectrumView.setFlip(false);
                    break;
            }
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                0, 0, WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        params.gravity = gravity;
        params.width = width;
        params.height = height;
        params.setTitle("SpectrumOverlay");
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0 /* types */);
        params.setTrustedOverlay();
        params.windowAnimations = 0;
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;

        if (mSpectrumWindowAdded && mSpectrumHost.getParent() != null) {
            // Already added to the window manager: refresh layout params in place (e.g. after
            // rotation) instead of removing and re-adding the window.
            Log.d(TAG, "attachSpectrumHostIfNeeded: updating layout params, width="
                    + params.width + " height=" + params.height + " gravity=" + params.gravity);
            try {
                mSpectrumWindowManager.updateViewLayout(mSpectrumHost, params);
            } catch (Exception e) {
                Log.e(TAG, "attachSpectrumHostIfNeeded: updateViewLayout failed: "
                        + e.getMessage(), e);
            }
            return;
        }

        Log.d(TAG, "attachSpectrumHostIfNeeded: adding view, width="
                + params.width + " height=" + params.height + " gravity=" + params.gravity
                + " type=" + params.type);
        try {
            mSpectrumWindowManager.addView(mSpectrumHost, params);
            mSpectrumWindowAdded = true;
            Log.d(TAG, "attachSpectrumHostIfNeeded: spectrum host added to window manager");
            final FrameLayout spectrumHost = mSpectrumHost;
            spectrumHost.post(() -> Log.d(TAG,
                    "spectrum host laid out: width=" + spectrumHost.getWidth()
                            + " height=" + spectrumHost.getHeight()));
        } catch (Exception e) {
            Log.e(TAG, "attachSpectrumHostIfNeeded: failed to add spectrum view: " + e.getMessage(), e);
        }
    }

    private void detachSpectrumHost() {
        Log.d(TAG, "detachSpectrumHost: removing spectrum host from window manager");
        if (mSpectrumHost != null) {
            // Detach the SpectrumView from the host before tearing the host down, otherwise a
            // later re-attach would fail with "View already has a parent" and the spectrum
            // window would never be shown again.
            if (mSpectrumView != null && mSpectrumView.getParent() == mSpectrumHost) {
                mSpectrumHost.removeView(mSpectrumView);
            }
            // The host may never have been added to the window manager (e.g. if addView failed
            // earlier), so only removeView when it actually has a parent window manager.
            if (mSpectrumWindowManager != null && mSpectrumHost.getParent() != null) {
                mSpectrumWindowManager.removeView(mSpectrumHost);
            }
            mSpectrumHost = null;
        }
        mSpectrumWindowAdded = false;
    }

    private void stopSpectrum() {
        Log.d(TAG, "stopSpectrum: stopping spectrum");
        detachSpectrumHost();
        if (mSpectrumManager != null) {
            mSpectrumManager.stop();
        }
    }

    /**
     * Starts the navbar variant of the spectrum. It is only ever shown once the keyguard is no
     * longer showing (i.e. after unlocking), so the navbar and lock screen variants never appear
     * at the same time.
     */
    private void startNavbarSpectrum() {
        ensureSpectrumManagerCreated();
        if (mSpectrumView == null) {
            mSpectrumView = new SpectrumView(mContext);
        }
        attachSpectrumHostIfNeeded();
        mSpectrumManager.setSpectrumView(mSpectrumView);
        mSpectrumManager.start();
        Log.d(TAG, "startNavbarSpectrum: spectrum started");
    }

    /** Detaches the navbar spectrum host and stops capture. */
    private void stopNavbarSpectrumLocked() {
        detachSpectrumHost();
        if (mSpectrumManager != null) {
            mSpectrumManager.stop();
        }
    }

    /**
     * Starts the lock screen variant of the spectrum. It lives inside the full-screen notification
     * shade window at the very bottom (index 0), i.e. above the wallpaper but below all keyguard
     * content (lock icon, charge state, ...), and it never intercepts touches to the lock screen.
     */
    private void startLockscreenSpectrum() {
        if (mNotificationShadeWindowController == null) {
            return;
        }
        ensureSpectrumManagerCreated();
        if (mLockscreenSpectrumView == null) {
            mLockscreenSpectrumView = new SpectrumView(mContext);
            mLockscreenSpectrumView.setVisibility(View.VISIBLE);
            // Sit below all keyguard content and never interfere with touches to the lock screen.
            mLockscreenSpectrumView.setClickable(false);
            mLockscreenSpectrumView.setFocusable(false);
            mLockscreenSpectrumView.setOnTouchListener((v, event) -> false);
        }
        attachLockscreenSpectrumHostIfNeeded();
        mSpectrumManager.setSpectrumView(mLockscreenSpectrumView);
        mSpectrumManager.start();
        Log.d(TAG, "startLockscreenSpectrum: spectrum started");
    }

    /** Removes the lock screen spectrum view and stops capture. */
    private void stopLockscreenSpectrumLocked() {
        detachLockscreenSpectrumHost();
        if (mSpectrumManager != null) {
            mSpectrumManager.stop();
        }
    }

    private void attachLockscreenSpectrumHostIfNeeded() {
        Log.d(TAG, "attachLockscreenSpectrumHostIfNeeded: mLockscreenSpectrumAdded="
                + mLockscreenSpectrumAdded + " parent=" + mLockscreenSpectrumView.getParent());
        // A view can only have one parent. Detach it from any previous host before re-adding.
        if (mLockscreenSpectrumView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mLockscreenSpectrumView.getParent()).removeView(mLockscreenSpectrumView);
        }

        final int height = mContext.getResources().getDimensionPixelSize(
                com.android.systemui.res.R.dimen.spectrum_lockscreen_height);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.gravity = Gravity.BOTTOM;

        if (mLockscreenSpectrumAdded && mLockscreenSpectrumView.getParent() != null) {
            // Already in the notification shade window: refresh the layout params (e.g. after rotation).
            mLockscreenSpectrumView.setLayoutParams(params);
            mLockscreenSpectrumView.requestLayout();
            Log.d(TAG,
                    "attachLockscreenSpectrumHostIfNeeded: refreshing lockscreen spectrum layout");
            return;
        }

        final ViewGroup windowRootView = mNotificationShadeWindowController.getWindowRootView();
        if (windowRootView == null) {
            Log.e(TAG, "attachLockscreenSpectrumHostIfNeeded: notification shade window root is null");
            return;
        }
        try {
            // Index 0 puts the view at the bottom of the full-screen notification shade window:
            // above the wallpaper but behind every keyguard view (lock icon, charge state, ...).
            windowRootView.addView(mLockscreenSpectrumView, 0, params);
            mLockscreenSpectrumAdded = true;
            Log.d(TAG,
                    "attachLockscreenSpectrumHostIfNeeded: lockscreen spectrum added to notification shade window");
        } catch (Exception e) {
            Log.e(TAG, "attachLockscreenSpectrumHostIfNeeded: failed to add lockscreen spectrum: "
                    + e.getMessage(), e);
        }
    }

    private void detachLockscreenSpectrumHost() {
        Log.d(TAG, "detachLockscreenSpectrumHost: removing lockscreen spectrum from notification shade window");
        if (mLockscreenSpectrumView != null
                && mLockscreenSpectrumView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mLockscreenSpectrumView.getParent()).removeView(mLockscreenSpectrumView);
        }
        mLockscreenSpectrumAdded = false;
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    boolean keyguardShowing = isKeyguardShowing(newState);
                    if (keyguardShowing != mKeyguardShowing) {
                        mKeyguardShowing = keyguardShowing;
                        updateSpectrumState();
                    }
                }
            };

    private static boolean isKeyguardShowing(int state) {
        return state == StatusBarState.KEYGUARD || state == StatusBarState.SHADE_LOCKED;
    }

    void addPipExclusionBoundsChangeListener(Pip pip) {
        pip.addPipExclusionBoundsChangeListener(mContext.getMainExecutor(), mPipListener);
    }

    void removePipExclusionBoundsChangeListener(Pip pip) {
        pip.removePipExclusionBoundsChangeListener(mPipListener);
    }

    /**
     * Returns {@code true} if this taskBar is {@link #init(int)}.
     * Returns {@code false} if this taskbar has not yet been {@link #init(int)}
     * or has been {@link #destroy()}.
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    private void parseCurrentSysuiState() {
        NavBarHelper.CurrentSysuiState state = mNavBarHelper.getCurrentSysuiState();
        if (state.mWindowStateDisplayId == mDefaultDisplayId) {
            mTaskBarWindowState = state.mWindowState;
        }
    }

    private void updateSysuiFlags() {
        long a11yFlags = mNavBarHelper.getA11yButtonState();
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiState.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_IME_VISIBLE,
                        (mNavbarFlags & NAVBAR_IME_VISIBLE) != 0)
                .setFlag(SYSUI_STATE_IME_SWITCHER_BUTTON_VISIBLE,
                        (mNavbarFlags & NAVBAR_IME_SWITCHER_BUTTON_VISIBLE) != 0)
                .setFlag(SYSUI_STATE_BACK_DISMISS_IME,
                        (mNavbarFlags & NAVBAR_BACK_DISMISS_IME) != 0)
                .setFlag(SYSUI_STATE_OVERVIEW_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                .setFlag(SYSUI_STATE_HOME_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                .setFlag(SYSUI_STATE_BACK_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isWindowVisible())
                .setFlag(SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY,
                        allowSystemGestureIgnoringBarVisibility())
                .commitUpdate(mDefaultDisplayId);
    }

    boolean isOverviewEnabled() {
        return (mSysUiState.getFlags() & View.STATUS_BAR_DISABLE_RECENT) == 0;
    }

    void onTransitionModeUpdated(int barMode, boolean checkBarModes) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().onTransitionModeUpdated(barMode, checkBarModes);
        } catch (RemoteException e) {
            Log.e(TAG, "onTransitionModeUpdated() failed, barMode: " + barMode, e);
        }
    }

    void checkNavBarModes(int displayId) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().checkNavBarModes(displayId);
        } catch (RemoteException e) {
            Log.e(TAG, "checkNavBarModes() failed", e);
        }
    }

    void finishBarAnimations(int displayId) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().finishBarAnimations(displayId);
        } catch (RemoteException e) {
            Log.e(TAG, "finishBarAnimations() failed", e);
        }
    }

    void touchAutoDim(int displayId) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            int state = mStatusBarStateController.getState();
            boolean shouldReset =
                    state != StatusBarState.KEYGUARD && state != StatusBarState.SHADE_LOCKED;
            mLauncherProxyService.getProxy().touchAutoDim(displayId, shouldReset);
        } catch (RemoteException e) {
            Log.e(TAG, "touchAutoDim() failed", e);
        }
    }

    void transitionTo(int displayId, @BarTransitions.TransitionMode int barMode, boolean animate) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().transitionTo(displayId, barMode, animate);
        } catch (RemoteException e) {
            Log.e(TAG, "transitionTo() failed, barMode: " + barMode, e);
        }
    }
    private void updateAssistantAvailability(boolean assistantAvailable,
            boolean longPressHomeEnabled) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().onAssistantAvailable(assistantAvailable,
                    longPressHomeEnabled);
        } catch (RemoteException e) {
            Log.e(TAG, "onAssistantAvailable() failed, available: " + assistantAvailable, e);
        }
    }

    private void updateWallpaperVisible(int displayId, boolean visible) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().updateWallpaperVisibility(displayId, visible);
        } catch (RemoteException e) {
            Log.e(TAG, "updateWallpaperVisibility() failed, visible: " + visible, e);
        }
    }

    private void appTransitionPending(boolean pending) {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().appTransitionPending(pending);
        } catch (RemoteException e) {
            Log.e(TAG, "appTransitionPending() failed, pending: " + pending, e);
        }
    }

    @Override
    public void setImeWindowStatus(int displayId, @ImeWindowVisibility int vis,
            @BackDispositionMode int backDisposition, boolean showImeSwitcherButton) {
        if (displayId != mDefaultDisplayId) {
            return;
        }

        final boolean isImeVisible = mNavBarHelper.isImeVisible(vis);
        final int flags = Utilities.updateNavbarFlagsFromIme(mNavbarFlags, backDisposition,
                isImeVisible, showImeSwitcherButton);
        if (flags == mNavbarFlags) {
            return;
        }

        mNavbarFlags = flags;
        updateSysuiFlags();
    }

    @Override
    public void setWindowState(int displayId, int window, int state) {
        if (displayId == mDefaultDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mTaskBarWindowState != state) {
            mTaskBarWindowState = state;
            updateSysuiFlags();
            updateSpectrumState();
        }
    }

    @Override
    public void onRotationProposal(int rotation, boolean isValid) {
        mLauncherProxyService.onRotationProposal(rotation, isValid);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId == mDefaultDisplayId) {
            mDisabledFlags = state1;
            updateSysuiFlags();
        }
        mLauncherProxyService.disable(displayId, state1, state2, animate);
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme, int behavior,
            @InsetsType int requestedVisibleTypes, String packageName,
            LetterboxDetails[] letterboxDetails) {
        mLauncherProxyService.onSystemBarAttributesChanged(displayId, behavior);
        if (displayId != mDefaultDisplayId) {
            return;
        }

        boolean nbModeChanged = false;
        if (mAppearance != appearance) {
            mAppearance = appearance;
            nbModeChanged = updateTransitionMode(
                    transitionMode(mTaskbarTransientShowing, appearance));
        }
        mLightBarController.onNavigationBarAppearanceChanged(appearance, nbModeChanged,
                mTransitionMode, navbarColorManagedByIme);
        if (mBehavior != behavior) {
            mBehavior = behavior;
            updateSysuiFlags();
        }
    }

    @Override
    public void showTransient(int displayId, @InsetsType int types, boolean isGestureOnSystemBar) {
        if (displayId != mDefaultDisplayId) {
            return;
        }
        if ((types & WindowInsets.Type.navigationBars()) == 0) {
            return;
        }
        if (!mTaskbarTransientShowing) {
            mTaskbarTransientShowing = true;
            onTransientStateChanged();
        }
        updateSpectrumState();
    }

    @Override
    public void abortTransient(int displayId, @InsetsType int types) {
        if (displayId != mDefaultDisplayId) {
            return;
        }
        if ((types & WindowInsets.Type.navigationBars()) == 0) {
            return;
        }
        clearTransient();
    }

    @Override
    public void onTaskbarAutohideSuspend(boolean suspend) {
        if (suspend) {
            mAutoHideController.suspendAutoHide();
        } else {
            mAutoHideController.resumeSuspendedAutoHide();
        }
    }

    @Override
    public void toggleTaskbar() {
        if (mLauncherProxyService.getProxy() == null) {
            return;
        }

        try {
            mLauncherProxyService.getProxy().onTaskbarToggled();
        } catch (RemoteException e) {
            Log.e(TAG, "onTaskbarToggled() failed", e);
        }
    }

    @Override
    public void appTransitionPending(int displayId, boolean forced) {
        appTransitionPending(true);
    }

    @Override
    public void appTransitionStarting(int displayId, long startTime, long duration,
            boolean forced) {
        appTransitionPending(false);
    }

    @Override
    public void appTransitionCancelled(int displayId) {
        appTransitionPending(false);
    }

    @Override
    public void appTransitionFinished(int displayId) {
        appTransitionPending(false);
    }

    private void clearTransient() {
        if (mTaskbarTransientShowing) {
            mTaskbarTransientShowing = false;
            onTransientStateChanged();
        }
        updateSpectrumState();
    }

    private void onTransientStateChanged() {
        mEdgeBackGestureHandler.onNavBarTransientStateChanged(mTaskbarTransientShowing);

        final int transitionMode = transitionMode(mTaskbarTransientShowing, mAppearance);
        if (updateTransitionMode(transitionMode)) {
            mLightBarController.onNavigationBarModeChanged(transitionMode);
        }
    }

    private boolean updateTransitionMode(int barMode) {
        if (mTransitionMode != barMode) {
            mTransitionMode = barMode;
            onTransitionModeUpdated(barMode, true);
            if (mAutoHideController != null) {
                mAutoHideController.touchAutoHide();
            }

            return true;
        }
        return false;
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavigationMode = mode;
        mEdgeBackGestureHandler.onNavigationModeChanged(mode);
        updateSpectrumState();
    }

    private boolean isWindowVisible() {
        return mTaskBarWindowState == WINDOW_STATE_SHOWING;
    }

    private boolean allowSystemGestureIgnoringBarVisibility() {
        return mBehavior != BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
    }

    @Override
    public void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        mLauncherProxyService.onNavigationBarLumaSamplingEnabled(displayId, enable);
    }

    @Override
    public void showPinningEnterExitToast(boolean entering) {
        updateSysuiFlags();
        if (mScreenPinningNotify == null) {
            return;
        }
        if (entering) {
            mScreenPinningNotify.showPinningStartToast();
        } else {
            mScreenPinningNotify.showPinningExitToast();
        }
    }

    @Override
    public void showPinningEscapeToast() {
        updateSysuiFlags();
        if (mScreenPinningNotify == null) {
            return;
        }
        mScreenPinningNotify.showEscapeToast(QuickStepContract.isGesturalMode(mNavigationMode),
                !QuickStepContract.isGesturalMode(mNavigationMode));
    }

    @VisibleForTesting
    int getNavigationMode() {
        return mNavigationMode;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("TaskbarDelegate (mDefaultDisplayId=" + mDefaultDisplayId + "):");
        pw.println("  mNavbarFlags=" + mNavbarFlags);
        pw.println("  mNavigationMode=" + mNavigationMode);
        pw.println("  mDisabledFlags=" + mDisabledFlags);
        pw.println("  mTaskBarWindowState=" + mTaskBarWindowState);
        pw.println("  mBehavior=" + mBehavior);
        pw.println("  mTaskbarTransientShowing=" + mTaskbarTransientShowing);
        mEdgeBackGestureHandler.dump(pw);
    }
}
