package com.android.launcher3.popup;

import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND;

import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_APP_INFO_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_WIDGETS_TAP;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.SuspendDialogInfo;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.userevent.nano.LauncherLogProto.Action;
import com.android.launcher3.userevent.nano.LauncherLogProto.ControlType;
import com.android.launcher3.util.InstantAppResolver;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.widget.WidgetsBottomSheet;

import java.util.List;

/**
 * Represents a system shortcut for a given app. The shortcut should have a label and icon, and an
 * onClickListener that depends on the item that the shortcut services.
 *
 * Example system shortcuts, defined as inner classes, include Widgets and AppInfo.
 * @param <T>
 */
public abstract class SystemShortcut<T extends BaseDraggingActivity> extends ItemInfo
        implements View.OnClickListener {

    private final int mIconResId;
    private final int mLabelResId;
    private final int mAccessibilityActionId;

    protected final T mTarget;
    protected final ItemInfo mItemInfo;

    public SystemShortcut(int iconResId, int labelResId, T target, ItemInfo itemInfo) {
        mIconResId = iconResId;
        mLabelResId = labelResId;
        mAccessibilityActionId = labelResId;
        mTarget = target;
        mItemInfo = itemInfo;
    }

    public SystemShortcut(SystemShortcut<T> other) {
        mIconResId = other.mIconResId;
        mLabelResId = other.mLabelResId;
        mAccessibilityActionId = other.mAccessibilityActionId;
        mTarget = other.mTarget;
        mItemInfo = other.mItemInfo;
    }

    /**
     * Should be in the left group of icons in app's context menu header.
     */
    public boolean isLeftGroup() {
        return false;
    }

    public void setIconAndLabelFor(View iconView, TextView labelView) {
        iconView.setBackgroundResource(mIconResId);
        labelView.setText(mLabelResId);
    }

    public void setIconAndContentDescriptionFor(ImageView view) {
        view.setImageResource(mIconResId);
        view.setContentDescription(view.getContext().getText(mLabelResId));
    }

    public AccessibilityNodeInfo.AccessibilityAction createAccessibilityAction(Context context) {
        return new AccessibilityNodeInfo.AccessibilityAction(
                mAccessibilityActionId, context.getText(mLabelResId));
    }

    public boolean hasHandlerForAction(int action) {
        return mAccessibilityActionId == action;
    }

    public interface Factory<T extends BaseDraggingActivity> {

        @Nullable SystemShortcut<T> getShortcut(T activity, ItemInfo itemInfo);
    }

    public static final Factory<Launcher> WIDGETS = (launcher, itemInfo) -> {
        if (itemInfo.getTargetComponent() == null) return null;
        final List<WidgetItem> widgets =
                launcher.getPopupDataProvider().getWidgetsForPackageUser(new PackageUserKey(
                        itemInfo.getTargetComponent().getPackageName(), itemInfo.user));
        if (widgets == null) {
            return null;
        }
        return new Widgets(launcher, itemInfo);
    };

    public static class Widgets extends SystemShortcut<Launcher> {
        public Widgets(Launcher target, ItemInfo itemInfo) {
            super(R.drawable.ic_widget, R.string.widget_button_text, target, itemInfo);
        }

        @Override
        public void onClick(View view) {
            AbstractFloatingView.closeAllOpenViews(mTarget);
            WidgetsBottomSheet widgetsBottomSheet =
                    (WidgetsBottomSheet) mTarget.getLayoutInflater().inflate(
                            R.layout.widgets_bottom_sheet, mTarget.getDragLayer(), false);
            widgetsBottomSheet.populateAndShow(mItemInfo);
            mTarget.getUserEventDispatcher().logActionOnControl(Action.Touch.TAP,
                    ControlType.WIDGETS_BUTTON, view);
            mTarget.getStatsLogManager().logger().withItemInfo(mItemInfo)
                    .log(LAUNCHER_SYSTEM_SHORTCUT_WIDGETS_TAP);
        }
    }

    public static final Factory<BaseDraggingActivity> APP_INFO = AppInfo::new;

    public static class AppInfo extends SystemShortcut {

        public AppInfo(BaseDraggingActivity target, ItemInfo itemInfo) {
            super(R.drawable.ic_info_no_shadow, R.string.app_info_drop_target_label, target,
                    itemInfo);
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView(mTarget);
            Rect sourceBounds = mTarget.getViewBounds(view);
            new PackageManagerHelper(mTarget).startDetailsActivityForInfo(
                    mItemInfo, sourceBounds, ActivityOptions.makeBasic().toBundle());
            mTarget.getUserEventDispatcher().logActionOnControl(Action.Touch.TAP,
                    ControlType.APPINFO_TARGET, view);
            mTarget.getStatsLogManager().logger().withItemInfo(mItemInfo)
                    .log(LAUNCHER_SYSTEM_SHORTCUT_APP_INFO_TAP);
        }
    }

    public static final Factory<BaseDraggingActivity> INSTALL = (activity, itemInfo) -> {
        boolean supportsWebUI = (itemInfo instanceof WorkspaceItemInfo)
                && ((WorkspaceItemInfo) itemInfo).hasStatusFlag(
                        WorkspaceItemInfo.FLAG_SUPPORTS_WEB_UI);
        boolean isInstantApp = false;
        if (itemInfo instanceof com.android.launcher3.model.data.AppInfo) {
            com.android.launcher3.model.data.AppInfo
                    appInfo = (com.android.launcher3.model.data.AppInfo) itemInfo;
            isInstantApp = InstantAppResolver.newInstance(activity).isInstantApp(appInfo);
        }
        boolean enabled = supportsWebUI || isInstantApp;
        if (!enabled) {
            return null;
        }
        return new Install(activity, itemInfo);
    };

    public static class Install extends SystemShortcut {

        public Install(BaseDraggingActivity target, ItemInfo itemInfo) {
            super(R.drawable.ic_install_no_shadow, R.string.install_drop_target_label,
                    target, itemInfo);
        }

        @Override
        public void onClick(View view) {
            Intent intent = new PackageManagerHelper(view.getContext()).getMarketIntent(
                    mItemInfo.getTargetComponent().getPackageName());
            mTarget.startActivitySafely(view, intent, mItemInfo, null);
            AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    public static final Factory<BaseDraggingActivity> PAUSE_APPS = (activity, itemInfo) -> {
        if (new PackageManagerHelper(activity).isAppSuspended(
                itemInfo.getTargetComponent().getPackageName(), itemInfo.user)) {
            return null;
        }
        return new PauseApps(activity, itemInfo);
    };

    public static class PauseApps extends SystemShortcut {

        public PauseApps(BaseDraggingActivity target, ItemInfo itemInfo) {
            super(R.drawable.ic_hourglass_top, R.string.paused_apps_drop_target_label, target,
                    itemInfo);
        }

        @Override
        public void onClick(View view) {
            CharSequence appLabel = view.getContext().getPackageManager().getApplicationLabel(
                    new PackageManagerHelper(view.getContext()).getApplicationInfo(
                            mItemInfo.getTargetComponent().getPackageName(), mItemInfo.user,0));
            new AlertDialog.Builder(view.getContext())
                    .setIcon(R.drawable.ic_hourglass_top)
                    .setTitle(view.getContext().getString(R.string.pause_apps_dialog_title,
                            appLabel))
                    .setMessage(view.getContext().getString(R.string.pause_apps_dialog_message,
                            appLabel))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.pause, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                AppGlobals.getPackageManager().setPackagesSuspendedAsUser(
                                        new String[]{
                                                mItemInfo.getTargetComponent().getPackageName()},
                                        true, null, null,
                                        new SuspendDialogInfo.Builder()
                                                .setIcon(R.drawable.ic_hourglass_top)
                                                .setTitle(R.string.paused_apps_dialog_title)
                                                .setMessage(R.string.paused_apps_dialog_message)
                                                .setNeutralButtonAction(BUTTON_ACTION_UNSUSPEND)
                                                .build(), view.getContext().getOpPackageName(),
                                        mItemInfo.user.getIdentifier());
                            } catch (RemoteException e) {
                                Log.e(SystemShortcut.class.getSimpleName(), "Failed to pause app", e);
                            }
                        }
                    })
                    .show();
            AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    public static void dismissTaskMenuView(BaseDraggingActivity activity) {
        AbstractFloatingView.closeOpenViews(activity, true,
            AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_REBIND_SAFE);
    }
}
