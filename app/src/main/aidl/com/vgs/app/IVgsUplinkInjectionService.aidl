package com.vgs.app;

import android.os.ParcelFileDescriptor;

interface IVgsUplinkInjectionService {
    boolean isPstnCallAudioInterceptable();
    ParcelFileDescriptor startUplinkInjection();
    void stopUplinkInjection();
    void destroy();
}
