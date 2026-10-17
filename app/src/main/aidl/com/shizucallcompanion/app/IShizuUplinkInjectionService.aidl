package com.shizucallcompanion.app;

import android.os.ParcelFileDescriptor;

interface IShizuUplinkInjectionService {
    boolean isPstnCallAudioInterceptable();
    ParcelFileDescriptor startUplinkInjection();
    void stopUplinkInjection();
    ParcelFileDescriptor startDownlinkCapture();
    void stopDownlinkCapture();
    void destroy();
}
