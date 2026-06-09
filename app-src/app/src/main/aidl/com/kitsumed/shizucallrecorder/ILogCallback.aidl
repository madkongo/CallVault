package com.kitsumed.shizucallrecorder;

interface ILogCallback {
    void onLogEvent(String level, String tag, String message, String throwableStackTrace);
}