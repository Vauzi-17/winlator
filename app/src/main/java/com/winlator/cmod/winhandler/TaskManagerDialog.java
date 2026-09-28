package com.winlator.cmod.winhandler;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Timer;
import java.util.TimerTask;

public class TaskManagerDialog extends ContentDialog implements OnGetProcessInfoListener {
    private final XServerDisplayActivity activity;
    private final LayoutInflater inflater;
    private Timer timer;
    private final Object lock = new Object();
    // Long press selects processes so one affinity can be applied to all of them.
    private final LinkedHashSet<Integer> selectedPids = new LinkedHashSet<>();
    private final HashMap<Integer, ProcessInfo> processInfos = new HashMap<>();
    private final Button newTaskButton;

    public TaskManagerDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.task_manager_dialog);
        this.activity = activity;
        setCancelable(false);
        setTitle(R.string.task_manager);
        setIcon(R.drawable.icon_task_manager);

        newTaskButton = findViewById(R.id.BTCancel);
        newTaskButton.setText(R.string.new_task);
        newTaskButton.setOnClickListener((v) -> {
            if (!selectedPids.isEmpty()) {
                showSelectionAffinityDialog();
                return;
            }
            dismiss();
            ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe", (command) -> activity.getWinHandler().exec(command));
        });

        setOnDismissListener((dialog) -> {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }

            activity.getWinHandler().setOnGetProcessInfoListener(null);
        });

        FileUtils.clear(getIconDir(activity));
        inflater = LayoutInflater.from(activity);
    }

    private void update() {
        synchronized (lock) {
            activity.getWinHandler().listProcesses();

            final LinearLayout container = findViewById(R.id.LLProcessList);
            if (container.getChildCount() == 0) findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
        }

        updateCPUInfoView();
        updateMemoryInfoView();
    }

    private void showListItemMenu(final View anchorView, final ProcessInfo processInfo) {
        PopupMenu listItemMenu = new PopupMenu(activity, anchorView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

        listItemMenu.inflate(R.menu.process_popup_menu);
        listItemMenu.setOnMenuItemClickListener((menuItem) -> {
            int itemId = menuItem.getItemId();
            final WinHandler winHandler = activity.getWinHandler();
            if (itemId == R.id.process_affinity) {
                showProcessorAffinityDialog(processInfo);
            }
            else if (itemId == R.id.bring_to_front) {
                winHandler.bringToFront(processInfo.name);
                dismiss();
            }
            else if (itemId == R.id.process_end) {
                ContentDialog.confirm(activity, R.string.do_you_want_to_end_this_process, () -> {
                    winHandler.killProcess(processInfo.name);
                });
            }
            return true;
        });
        listItemMenu.show();
    }

    private void showProcessorAffinityDialog(final ProcessInfo processInfo) {
        ContentDialog dialog = new ContentDialog(activity, R.layout.cpu_list_dialog);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = dialog.findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(processInfo.getCPUList());
        dialog.setOnConfirmCallback(() -> {
            WinHandler winHandler = activity.getWinHandler();
            winHandler.setProcessAffinity(processInfo.pid, ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList()));
            update();
        });
        dialog.show();
    }

    private void toggleSelection(int pid) {
        if (!selectedPids.remove(pid)) selectedPids.add(pid);
        updateSelectionViews();
    }

    private void updateSelectionViews() {
        if (selectedPids.isEmpty()) newTaskButton.setText(R.string.new_task);
        else newTaskButton.setText(activity.getString(R.string.set_affinity_of_selected, selectedPids.size()));

        final LinearLayout container = findViewById(R.id.LLProcessList);
        for (int i = 0; i < container.getChildCount(); i++) {
            View itemView = container.getChildAt(i);
            Object pid = itemView.getTag();
            itemView.setBackgroundColor(pid != null && selectedPids.contains((Integer)pid) ? 0x400288d1 : 0);
        }
    }

    private void showSelectionAffinityDialog() {
        ContentDialog dialog = new ContentDialog(activity, R.layout.cpu_list_dialog);
        dialog.setTitle(activity.getString(R.string.processor_affinity)+" ("+selectedPids.size()+")");
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = dialog.findViewById(R.id.CPUListView);
        ProcessInfo first = processInfos.get(selectedPids.iterator().next());
        if (first != null) cpuListView.setCheckedCPUList(first.getCPUList());
        dialog.setOnConfirmCallback(() -> {
            WinHandler winHandler = activity.getWinHandler();
            int affinityMask = ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList());
            for (int pid : selectedPids) winHandler.setProcessAffinity(pid, affinityMask);
            selectedPids.clear();
            updateSelectionViews();
            update();
        });
        dialog.show();
    }

    public static File getIconDir(Context context) {
        File iconDir = new File(ImageFs.find(context).getRootDir(), "home/xuser/.local/share/icons/taskmgr");
        if (!iconDir.isDirectory()) iconDir.mkdirs();
        return iconDir;
    }

    @Override
    public void show() {
        update();
        activity.getWinHandler().setOnGetProcessInfoListener(this);

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.runOnUiThread(TaskManagerDialog.this::update);
            }
        }, 0, 1000);
        super.show();
    }

    @Override
    public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            synchronized (lock) {
                final LinearLayout container = findViewById(R.id.LLProcessList);
                setBottomBarText(activity.getString(R.string.processes)+": " + numProcesses+" \u2022 "+activity.getString(R.string.long_press_to_select_processes));

                if (numProcesses == 0) {
                    container.removeAllViews();
                    processInfos.clear();
                    selectedPids.clear();
                    updateSelectionViews();
                    findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                    return;
                }

                findViewById(R.id.TVEmptyText).setVisibility(View.GONE);

                int childCount = container.getChildCount();
                View itemView = index < childCount ? container.getChildAt(index) : inflater.inflate(R.layout.process_info_list_item, container, false);
                ((TextView)itemView.findViewById(R.id.TVName)).setText(processInfo.name+(processInfo.wow64Process ? " *32" : ""));
                ((TextView)itemView.findViewById(R.id.TVPID)).setText(String.valueOf(processInfo.pid));
                ((TextView)itemView.findViewById(R.id.TVMemoryUsage)).setText(processInfo.getFormattedMemoryUsage());
                itemView.findViewById(R.id.BTMenu).setOnClickListener((v) -> showListItemMenu(v, processInfo));
                processInfos.put(processInfo.pid, processInfo);
                itemView.setTag(processInfo.pid);
                itemView.setOnLongClickListener((v) -> {
                    toggleSelection(processInfo.pid);
                    return true;
                });
                itemView.setOnClickListener((v) -> {
                    if (!selectedPids.isEmpty()) toggleSelection(processInfo.pid);
                });
                itemView.setBackgroundColor(selectedPids.contains(processInfo.pid) ? 0x400288d1 : 0);

                XServer xServer = activity.getXServer();
                Window window;

                try (XLock xlock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    window = xServer.windowManager.findWindowWithProcessId(processInfo.pid);
                }

                ImageView ivIcon = itemView.findViewById(R.id.IVIcon);
                ivIcon.setImageResource(R.drawable.taskmgr_process);
                if (window != null) {
                    Bitmap icon = xServer.pixmapManager.getWindowIcon(window);
                    if (icon != null) ivIcon.setImageBitmap(icon);
                }

                if (index >= childCount) container.addView(itemView);

                if (index == numProcesses-1) {
                    if (childCount > numProcesses) {
                        for (int i = childCount-1; i >= numProcesses; i--) container.removeViewAt(i);
                    }
                    // Forget processes that ended.
                    LinkedHashSet<Integer> alivePids = new LinkedHashSet<>();
                    for (int i = 0; i < container.getChildCount(); i++) {
                        Object pid = container.getChildAt(i).getTag();
                        if (pid != null) alivePids.add((Integer)pid);
                    }
                    processInfos.keySet().retainAll(alivePids);
                    if (selectedPids.retainAll(alivePids)) updateSelectionViews();
                }
            }
        });
    }

    private void updateCPUInfoView() {
        LinearLayout llCPUInfo = findViewById(R.id.LLCPUInfo);
        llCPUInfo.removeAllViews();
        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        int totalClockSpeed = 0;
        short maxClockSpeed = 0;

        for (int i = 0; i < clockSpeeds.length; i++) {
            TextView textView = new TextView(activity);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            short clockSpeed = CPUStatus.getMaxClockSpeed(i);
            textView.setText(clockSpeeds[i]+"/"+clockSpeed+" MHz");
            llCPUInfo.addView(textView);
            totalClockSpeed += clockSpeeds[i];
            maxClockSpeed = (short)Math.max(maxClockSpeed, clockSpeed);
        }

        int avgClockSpeed = totalClockSpeed / clockSpeeds.length;
        TextView tvCPUTitle = findViewById(R.id.TVCPUTitle);
        byte cpuUsagePercent = (byte)(((float)avgClockSpeed / maxClockSpeed) * 100.0f);
        tvCPUTitle.setText("CPU ("+cpuUsagePercent+"%)");
    }

    private void updateMemoryInfoView() {
        ActivityManager activityManager = (ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        byte memUsagePercent = (byte)(((double)usedMem / memoryInfo.totalMem) * 100.0f);

        TextView tvMemoryTitle = findViewById(R.id.TVMemoryTitle);
        tvMemoryTitle.setText(activity.getString(R.string.memory)+" ("+memUsagePercent+"%)");

        TextView tvMemoryInfo = findViewById(R.id.TVMemoryInfo);
        tvMemoryInfo.setText(StringUtils.formatBytes(usedMem, false)+"/"+StringUtils.formatBytes(memoryInfo.totalMem));
    }
}
