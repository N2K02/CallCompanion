package com.vgs.app;

oneway interface IVgsCallAudioListener {
    void onPcmFrame(in byte[] pcm);
    void onCaptureState(String state);
}
