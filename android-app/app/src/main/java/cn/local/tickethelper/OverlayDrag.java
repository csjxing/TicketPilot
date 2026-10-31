package cn.local.tickethelper;

import android.graphics.Insets;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.util.function.BiConsumer;

/** Local overlay movement only. It never sends gestures or clicks to another app. */
final class OverlayDrag {
    private OverlayDrag() { }
    static void keepOnScreen(View root,WindowManager windows) {
        root.post(()->{
            if(!root.isAttachedToWindow() || !(root.getLayoutParams() instanceof WindowManager.LayoutParams))return;
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)root.getLayoutParams();
            android.view.WindowMetrics m=windows.getCurrentWindowMetrics();
            Insets bars=m.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            int x=Math.max(0,Math.min(m.getBounds().width()-root.getWidth(),p.x));
            int y=Math.max(0,Math.min(m.getBounds().height()-bars.top-bars.bottom-root.getHeight(),p.y));
            if(x!=p.x || y!=p.y){p.x=x;p.y=y;try{windows.updateViewLayout(root,p);}catch(RuntimeException detached){}}
        });
    }
    static void bind(View handle,View root,WindowManager windows,BiConsumer<Integer,Integer> moved) {
        int slop=ViewConfiguration.get(handle.getContext()).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX,downY;int x,y;boolean dragging;
            @Override public boolean onTouch(View view,MotionEvent event) {
                if(!(root.getLayoutParams() instanceof WindowManager.LayoutParams))return false;
                WindowManager.LayoutParams p=(WindowManager.LayoutParams)root.getLayoutParams();
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX=event.getRawX();downY=event.getRawY();x=p.x;y=p.y;dragging=false;return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                        if(!dragging && Math.hypot(dx,dy)<slop)return true;
                        dragging=true;
                        android.view.WindowMetrics m=windows.getCurrentWindowMetrics();
                        Insets bars=m.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                        p.x=Math.max(0,Math.min(m.getBounds().width()-root.getWidth(),x+Math.round(dx)));
                        p.y=Math.max(0,Math.min(m.getBounds().height()-bars.top-bars.bottom-root.getHeight(),y+Math.round(dy)));
                        try{windows.updateViewLayout(root,p);moved.accept(p.x,p.y);}catch(RuntimeException detached){return false;}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if(!dragging)view.performClick();return true;
                    case MotionEvent.ACTION_CANCEL:return true;
                    default:return false;
                }
            }
        });
    }
}
