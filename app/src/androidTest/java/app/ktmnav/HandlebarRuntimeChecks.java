package app.ktmnav;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import app.ktmnav.backend.HandlebarController;
import app.ktmnav.backend.HandlebarMappings;
import app.ktmnav.backend.Bridge;
import app.ktmnav.backend.BikeGeneration;
import app.ktmnav.backend.BccuBleTransport;
import app.ktmnav.backend.BccuCrypto;
import app.ktmnav.backend.DashboardFeatures;
import app.ktmnav.backend.ConnectionState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real Android actions on a controlled, task-owned emulator; never talks to a motorcycle. */
final class HandlebarRuntimeChecks {
    private final Instrumentation runner;
    private final List<String> results=new ArrayList<>();
    HandlebarRuntimeChecks(Instrumentation runner){this.runner=runner;}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);results.add(message);}
    private void waitFor(BooleanSupplier condition,String failure) throws InterruptedException {
        for(int attempt=0;attempt<40;attempt++){if(condition.getAsBoolean())return;Thread.sleep(100);}
        throw new AssertionError(failure+"\nObserved action results: "+results);
    }
    String denied() {
        HandlebarController controller=new HandlebarController(runner.getTargetContext());
        check(!HandlebarController.isAccessibilityConnected(),"Screen access starts disconnected when Android has not enabled service");
        String back=controller.execute(HandlebarMappings.Action.BACK);
        check(back.toLowerCase(java.util.Locale.ROOT).contains("not connected"),"Mapped Back reports missing access instead of performing screen action");
        String media=controller.execute(HandlebarMappings.Action.MEDIA_NEXT);
        check(media.toLowerCase(java.util.Locale.ROOT).contains("access"),"Mapped media reports missing notification access");
        return "PASS: missing-access checks\n"+String.join("\n",results)+"\n";
    }
    String run() throws Exception {
        Context context=runner.getTargetContext();
        waitFor(HandlebarController::isAccessibilityConnected,"Accessibility service did not bind after explicit emulator setup");
        HandlebarController controller=new HandlebarController(context);
        SharedPreferences prefs=context.getSharedPreferences("ktmnav",Context.MODE_PRIVATE);
        String key=HandlebarMappings.preferenceKey("UP");boolean existed=prefs.contains(key);String old=prefs.getString(key,null);
        try {
            prefs.edit().putString(key,"MEDIA_NEXT").commit();
            check(HandlebarMappings.resolve("UP",prefs.getString(key,null))==HandlebarMappings.Action.MEDIA_NEXT,"Saved mapping resolves through production code");
            check(HandlebarMappings.resolve("UP","CRUISE")==HandlebarMappings.Action.NONE,"Unsupported action is rejected");
        } finally {SharedPreferences.Editor editor=prefs.edit();if(existed)editor.putString(key,old);else editor.remove(key);editor.commit();}

        Activity activity=runner.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ScrollView[] scroll={null};Button[] first={null},second={null};AtomicInteger clicks=new AtomicInteger();
        try {
            runner.runOnMainSync(()->{
                LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
                first[0]=new Button(activity);first[0].setText("First test control");first[0].setFocusableInTouchMode(true);root.addView(first[0]);
                second[0]=new Button(activity);second[0].setText("Second test control");second[0].setFocusableInTouchMode(true);second[0].setOnClickListener(v->clicks.incrementAndGet());root.addView(second[0]);
                scroll[0]=new ScrollView(activity);LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
                for(int n=0;n<50;n++){Button row=new Button(activity);row.setText("Scroll test row "+n);row.setMinimumHeight(120);content.addView(row);}
                scroll[0].addView(content);root.addView(scroll[0],new LinearLayout.LayoutParams(-1,0,1));activity.setContentView(root);first[0].requestFocus();
            });
            runner.waitForIdleSync();Thread.sleep(500);
            runner.runOnMainSync(()->results.add("Scroll down result: "+controller.execute(HandlebarMappings.Action.SCROLL_DOWN)));
            waitFor(()->scroll[0].getScrollY()>0,"Mapped SCROLL_DOWN did not scroll actual Android view");
            check(scroll[0].getScrollY()>0,"Mapped SCROLL_DOWN changes actual scroll position");
            runner.runOnMainSync(()->results.add("Scroll up result: "+controller.execute(HandlebarMappings.Action.SCROLL_UP)));
            waitFor(()->scroll[0].getScrollY()==0,"Mapped SCROLL_UP did not return Android view to top");
            check(true,"Mapped SCROLL_UP returns actual view to top");
            runner.runOnMainSync(()->{first[0].requestFocus();results.add("Next focus result: "+controller.execute(HandlebarMappings.Action.FOCUS_NEXT));});
            waitFor(()->second[0].hasFocus(),"Mapped FOCUS_NEXT did not move input focus");
            runner.runOnMainSync(()->results.add("Select result: "+controller.execute(HandlebarMappings.Action.CLICK_FOCUSED)));
            waitFor(()->clicks.get()==1,"Mapped CLICK_FOCUSED did not activate the focused test button");
            check(clicks.get()==1,"Mapped SELECT activates focused control exactly once");
            runner.runOnMainSync(()->results.add("Previous focus result: "+controller.execute(HandlebarMappings.Action.FOCUS_PREVIOUS)));
            waitFor(()->first[0].hasFocus(),"Mapped FOCUS_PREVIOUS did not restore input focus");
            check(true,"Mapped previous focus restores actual control focus");
            AlertDialog[] dialog={null};runner.runOnMainSync(()->dialog[0]=new AlertDialog.Builder(activity).setTitle("Handlebar Back test").setPositiveButton("Close",null).show());
            Thread.sleep(500);runner.runOnMainSync(()->results.add("Android Back result: "+controller.execute(HandlebarMappings.Action.BACK)));
            waitFor(()->!dialog[0].isShowing(),"Mapped Android BACK did not dismiss real dialog");check(true,"Mapped BACK dismisses Android dialog");
            testMedia(context,controller);
            testVolume(context,controller);
            testReceivedButtons(context);
        } finally {runner.runOnMainSync(activity::finish);}
        return "PASS: real Android handlebar-action checks\n"+String.join("\n",results)+"\nNo physical motorcycle connected; no vehicle commands sent.\n";
    }
    private void testMedia(Context context,HandlebarController controller) throws Exception {
        List<String> callbacks=Collections.synchronizedList(new ArrayList<>());MediaSession[] session={null};
        runner.runOnMainSync(()->{
            session[0]=new MediaSession(context,"KTMnav mapping test");
            session[0].setCallback(new MediaSession.Callback(){
                @Override public void onPause(){callbacks.add("pause");}
                @Override public void onPlay(){callbacks.add("play");}
                @Override public void onSkipToNext(){callbacks.add("next");}
                @Override public void onSkipToPrevious(){callbacks.add("previous");}
            },new Handler(Looper.getMainLooper()));
            session[0].setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING,0,1f).setActions(PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS).build());session[0].setActive(true);
        });
        try {
            Thread.sleep(500);
            for(HandlebarMappings.Action action:new HandlebarMappings.Action[]{HandlebarMappings.Action.MEDIA_PLAY_PAUSE,HandlebarMappings.Action.MEDIA_NEXT,HandlebarMappings.Action.MEDIA_PREVIOUS})runner.runOnMainSync(()->results.add(action.name()+": "+controller.execute(action)));
            waitFor(()->callbacks.contains("pause")&&callbacks.contains("next")&&callbacks.contains("previous"),"Mapped media actions did not reach actual Android MediaSession callbacks: "+callbacks);
            check(true,"Mapped media play/pause, next and previous reach real session callbacks");
        } finally {runner.runOnMainSync(()->session[0].release());}
    }
    private void testVolume(Context context,HandlebarController controller) {
        AudioManager audio=context.getSystemService(AudioManager.class);int old=audio.getStreamVolume(AudioManager.STREAM_MUSIC);int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        try {
            int initial=Math.max(1,max/2);audio.setStreamVolume(AudioManager.STREAM_MUSIC,initial,0);
            runner.runOnMainSync(()->results.add("Volume up: "+controller.execute(HandlebarMappings.Action.VOLUME_UP)));
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)>initial,"Mapped volume up changes music stream volume");
            runner.runOnMainSync(()->results.add("Volume down: "+controller.execute(HandlebarMappings.Action.VOLUME_DOWN)));
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial,"Mapped volume down restores music stream volume");
        } finally {audio.setStreamVolume(AudioManager.STREAM_MUSIC,old,0);}
    }
    private Object field(Object target,String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private void field(Object target,String name,Object value) {
        try{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private void press(DashboardFeatures features,int mask,byte[] key,byte[] iv,boolean active) {
        byte[] payload=new byte[32];payload[16]=active?(byte)0xff:0;payload[17]=(byte)mask;
        features.handleRcm(BccuCrypto.INSTANCE.encryptControl(payload,key,iv));
    }
    private void tap(DashboardFeatures features,int mask,byte[] key,byte[] iv) {
        runner.runOnMainSync(()->{press(features,mask,key,iv,true);press(features,0,key,iv,true);});
    }
    private void testReceivedButtons(Context context) throws Exception {
        Bridge bridge=Bridge.get(context);SharedPreferences prefs=bridge.getPrefs();
        String address="02:00:00:4B:54:5A";AudioManager audio=context.getSystemService(AudioManager.class);
        int volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);int initial=Math.max(2,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2);
        List<Integer> masks=Collections.synchronizedList(new ArrayList<>());BccuBleTransport[] transport={null};DashboardFeatures[] features={null};
        app.ktmnav.backend.HandlebarMaskListener recorder=(mask,uptimeMs)->masks.add(mask);
        byte[] key=new byte[16],iv=new byte[16];
        try {
            runner.runOnMainSync(()->{
                prefs.edit().putBoolean("keep_background",false).putBoolean("handlebar_mappings_enabled",true)
                    .putString(HandlebarMappings.PREF_MODE,"CUSTOM")
                    .putString(HandlebarMappings.preferenceKey("UP"),"VOLUME_UP").putString(HandlebarMappings.preferenceKey("DOWN"),"VOLUME_DOWN")
                    .putString(HandlebarMappings.preferenceKey("DOWN",HandlebarMappings.Gesture.DOUBLE),"VOLUME_UP")
                    .putString(HandlebarMappings.preferenceKey("SET"),"NONE").putString(HandlebarMappings.preferenceKey("BACK"),"NONE").commit();
                bridge.addHandlebarMaskListener(recorder);
                bridge.connect(address,"Synthetic handlebar transport",BikeGeneration.GEN3);
                try {
                    transport[0]=(BccuBleTransport)field(bridge,"activeTransport");
                    field(transport[0],"state",ConnectionState.CONNECTED);field(bridge,"connectionState",ConnectionState.CONNECTED);
                    check(field(transport[0],"onHandlebarMaskListener")!=null,"Production Bridge attaches the real handlebar mask listener");
                    features[0]=(DashboardFeatures)field(transport[0],"dashboardFeatures");
                    field(features[0],"sessionKey",key);field(features[0],"sessionIv",iv);field(features[0],"authenticated",true);
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,initial,0);
                for(int mask:new int[]{8,0,8,8,0})press(features[0],mask,key,iv,true);
            });
            waitFor(()->audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+2,"Two UP taps (one with a short hold) did not raise volume twice");
            check(masks.size()==5,"Encrypted RCM frames reach the gesture router in order");
            check(true,"UP tap mapping executes the real volume action through Bridge, held frames do not repeat");
            tap(features[0],4,key,iv);Thread.sleep(80);tap(features[0],4,key,iv);
            Thread.sleep(600);runner.waitForIdleSync();
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+3,"DOWN double tap runs its double-tap action instead of two single taps");
            tap(features[0],4,key,iv);Thread.sleep(600);runner.waitForIdleSync();
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+2,"Single DOWN tap runs its tap action after the double-tap window");
            runner.runOnMainSync(()->press(features[0],8,key,iv,true));Thread.sleep(550);runner.runOnMainSync(()->press(features[0],0,key,iv,true));
            waitFor(()->bridge.getHandlebarRouter().currentMode()==HandlebarMappings.Mode.MUSIC,"Long-press UP did not change the button mode");
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+2,"Long-press UP changes mode (Custom to Music) without running the tap action");
            tap(features[0],8,key,iv);waitFor(()->audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+3,"Music mode UP tap did not raise volume");
            check(true,"Music mode preset maps UP tap to volume up");
            runner.runOnMainSync(()->prefs.edit().putString(HandlebarMappings.PREF_MODE,"CUSTOM").commit());
            int beforeInactive=masks.size();
            runner.runOnMainSync(()->{press(features[0],8,key,iv,false);press(features[0],0,key,iv,false);});runner.waitForIdleSync();Thread.sleep(300);
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+3,"Frames without the active flag never trigger an action");
            check(masks.size()==beforeInactive+2,"Inactive frames are reported as released (mask 0)");
            int before=masks.size();runner.runOnMainSync(()->features[0].onRssi(-60));runner.waitForIdleSync();
            check(masks.size()==before,"RSSI refresh does not replay a handlebar action");
            runner.runOnMainSync(()->prefs.edit().putBoolean("handlebar_mappings_enabled",false).commit());
            tap(features[0],8,key,iv);Thread.sleep(300);runner.waitForIdleSync();
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+3,"Disabling custom mappings prevents received UP action execution");
            runner.runOnMainSync(()->prefs.edit().putBoolean("handlebar_mappings_enabled",true).commit());
            runner.runOnMainSync(()->{press(features[0],4,key,iv,true);press(features[0],0,key,iv,true);bridge.disconnect();});
            Thread.sleep(600);runner.waitForIdleSync();
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==initial+3,"Disconnect cancels a pending double-tap decision from the old transport");
        } finally {
            runner.runOnMainSync(()->{bridge.removeHandlebarMaskListener(recorder);bridge.disconnect();bridge.forgetBike(address);SharedPreferences.Editor edit=prefs.edit().putBoolean("keep_background",true).putBoolean("handlebar_mappings_enabled",false).remove(HandlebarMappings.PREF_MODE);
                for(String b:HandlebarMappings.buttons())for(HandlebarMappings.Gesture g:HandlebarMappings.Gesture.values())edit.remove(HandlebarMappings.preferenceKey(b,g));edit.commit();});
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);
        }
    }
}
