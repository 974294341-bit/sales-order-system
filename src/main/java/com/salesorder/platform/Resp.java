package com.salesorder.platform;

/** 控制器返回值：默认 200，需要 201 / 204 等状态时显式构造。 */
public final class Resp {
    public final int status;
    public final Object body;

    private Resp(int status, Object body) {
        this.status = status;
        this.body = body;
    }

    public static Resp ok(Object body) { return new Resp(200, body); }

    public static Resp created(Object body) { return new Resp(201, body); }

    public static Resp noContent() { return new Resp(204, null); }
}
