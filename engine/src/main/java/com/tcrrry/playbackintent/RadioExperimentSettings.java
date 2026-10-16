package com.tcrrry.playbackintent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginSettingsSession;

final class RadioExperimentSettings implements PluginSettingsSession {
    private final LinearLayout body;
    private final TextView status;
    private final Switch repeat;
    private final Button restore;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RadioRepeatExperiment radio;
    private final HostContract host;
    private final RepeatCommand command;
    private final PluginContext runtime;
    private boolean closed, rendering;
    private RadioRepeatExperiment.Snapshot displayed=RadioRepeatExperiment.Snapshot.unavailable(null);
    private final Runnable poll=new Runnable() { public void run() { refresh(); if(!closed) main.postDelayed(this,700); } };

    RadioExperimentSettings(Context context,RadioRepeatExperiment radio,HostContract host,
            RepeatCommand command,PluginContext runtime) {
        this.radio=radio; this.host=host; this.command=command; this.runtime=runtime;
        int pad=(int)(20*context.getResources().getDisplayMetrics().density+.5f);
        body=new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(context); title.setText("电台与自动播放循环实验"); title.setTextSize(20); body.addView(title);
        status=new TextView(context); status.setTextSize(15); status.setMaxLines(4);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(0,pad,0,pad); body.addView(status);
        repeat=new Switch(context); repeat.setText("当前队列单曲循环"); body.addView(repeat);
        restore=new Button(context); restore.setText("恢复顺序播放");
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2); layout.topMargin=pad; body.addView(restore,layout);
        repeat.setOnCheckedChangeListener((button,enabled) -> { if(!rendering) change(enabled); });
        restore.setOnClickListener(v -> change(false));
        main.post(poll);
    }
    private void refresh() {
        if(closed) return;
        try {
            displayed=host.radioSnapshot(radio.latest());
            boolean selected=displayed.manager!=null && radio.wantsOverride(displayed.manager)
                    && radio.effective(displayed.manager,0,displayed)==1;
            rendering=true;
            repeat.setChecked(selected); repeat.setEnabled(displayed.eligible);
            restore.setEnabled(selected);
            status.setText(displayed.eligible ? (displayed.title==null ? "" : displayed.title)+"\n"+(selected ? "单曲循环（实验）" : "顺序播放")
                    : "当前内容不支持电台或自动播放循环实验");
        } catch(Exception error) {
            rendering=true; repeat.setChecked(false); repeat.setEnabled(false); restore.setEnabled(false);
            status.setText("当前播放状态暂不可用"); runtime.log("Radio experiment status unavailable",error);
        } finally { rendering=false; }
    }
    private void change(boolean enable) {
        try {
            command.change(displayed,enable);
        } catch(Exception error) { runtime.log("Radio experiment command rejected",error); }
        refresh();
    }
    @Override public android.view.View getView() { return body; }
    @Override public void close() {
        closed=true; main.removeCallbacks(poll); repeat.setOnCheckedChangeListener(null); restore.setOnClickListener(null);
        displayed=RadioRepeatExperiment.Snapshot.unavailable(null);
    }
}
