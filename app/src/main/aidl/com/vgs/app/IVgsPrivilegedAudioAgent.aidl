package com.vgs.app;

interface IVgsPrivilegedAudioAgent {
    void startCapture(IVgsCallAudioListener listener);
    void stopCapture();
    boolean isCapturing();
}
