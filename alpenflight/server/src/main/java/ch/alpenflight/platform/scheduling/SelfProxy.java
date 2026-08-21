package ch.alpenflight.platform.scheduling;

import org.springframework.beans.factory.ObjectProvider;

public final class SelfProxy<T> {

    private final ObjectProvider<T> springProxyAroundThisBean;

    private SelfProxy(ObjectProvider<T> springProxyAroundThisBean) {
        this.springProxyAroundThisBean = springProxyAroundThisBean;
    }

    public static <T> SelfProxy<T> around(ObjectProvider<T> springProxyAroundThisBean) {
        return new SelfProxy<>(springProxyAroundThisBean);
    }

    public T soTheTransactionalBoundaryApplies() {
        return springProxyAroundThisBean.getObject();
    }

    public T soTheJobRunRecordIsWritten() {
        return springProxyAroundThisBean.getObject();
    }
}
