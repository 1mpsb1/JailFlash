package ru.cuxtoms.flashx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class FlashXTileService extends TileService {

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TorchService.ACTION_STATE.equals(intent.getAction())) {
                updateTile();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(TorchService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean isOn = LedController.get(this).isOn();
        Intent i = new Intent(this, TorchService.class);
        
        if (isOn) {
            i.setAction(TorchService.ACTION_OFF);
            startService(i);
        } else {
            i.setAction(TorchService.ACTION_TOGGLE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
        
        updateTile();
    }

    private void updateTile() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(LedController.get(this).isOn() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setLabel("JailFlash");
            t.updateTile();
        }
    }
}