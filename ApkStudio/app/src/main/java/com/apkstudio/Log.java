package com.apkstudio;

/** Simple callback sink so the engine can stream progress lines to the UI. */
public interface Log {
    void line(String s);
}
