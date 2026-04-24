package com.wzydqq.icu.injector;

import android.content.Context;

import com.github.megatronking.netbare.http.HttpInjectInterceptor;
import com.github.megatronking.netbare.http.HttpInterceptor;
import com.github.megatronking.netbare.http.HttpInterceptorFactory;

import androidx.annotation.NonNull;

/**
 * 位置伪装拦截器工厂
 * 创建 LocationHttpInjector 的 HttpInterceptorFactory 实例
 */
public class LocationInterceptorFactory implements HttpInterceptorFactory {

    private final HttpInterceptorFactory delegate;

    public LocationInterceptorFactory(Context context) {
        // 使用 NetBare 提供的 HttpInjectInterceptor.createFactory 来创建工厂
        this.delegate = HttpInjectInterceptor.createFactory(
                new LocationHttpInjector(context.getApplicationContext()));
    }

    @NonNull
    @Override
    public HttpInterceptor create() {
        return delegate.create();
    }
}
