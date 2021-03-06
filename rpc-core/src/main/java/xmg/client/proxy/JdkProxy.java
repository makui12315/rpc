package xmg.client.proxy;

import io.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import xmg.client.RpcClient;
import xmg.client.connect.ConnectionManager;
import xmg.client.handler.ClientHandler;
import xmg.client.support.RpcApi;
import xmg.client.support.RpcMode;
import xmg.codec.Request;
import xmg.codec.exception.RpcRemoteApiException;
import xmg.server.RpcServer;
import xmg.server.handler.ServerHandler;
import xmg.utils.StringUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class JdkProxy {
    private static final Logger log = LoggerFactory.getLogger(RpcServer.class);
    private static Environment environment;
    private static final JdkProxy proxy = new JdkProxy();

    public static JdkProxy getInstance() {
        return proxy;
    }

    private JdkProxy() {
    }

    public void setEnvironment(Environment environment) {
        JdkProxy.environment = environment;
    }

    public static Environment getEnvironment() {
        return environment;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProxyInstance(Class<T> interfaceClass) {
        final Object o = Proxy.newProxyInstance(interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                new Handler(interfaceClass));
        return (T) o;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProxyInstance(Class<T> interfaceClass, ClientHandler handler) {
        final Object o = Proxy.newProxyInstance(interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                new Handler0(interfaceClass, handler));
        return (T) o;
    }

    static class Handler0 extends Handler {
        private final ClientHandler handler;

        Handler0(final Class<?> target, final ClientHandler handler) {
            super(target);
            this.handler = handler;
        }

        @Override
        public ClientHandler getHandler(Integer nodeId) {
            return this.handler;
        }
    }

    static class Handler implements InvocationHandler {
        private final Class<?> target;

        Handler(Class<?> target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws InterruptedException {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        throw new UnsupportedOperationException();
                }
            }
            final RpcApi rpcApi = target.getAnnotation(RpcApi.class);
            if (rpcApi == null) {
                throw new RuntimeException("is not rpcApi");
            }
            final String masterId = RpcClient.resolverValue(rpcApi.masterId(), environment);
            final RpcMode rpcMode = method.getAnnotation(RpcMode.class);
            ClientHandler handler;
            if (rpcMode != null && "post".equalsIgnoreCase(rpcMode.value()) && StringUtils.isNotBlank(masterId)) {
                handler = getHandler(Integer.parseInt(masterId));
            } else {
                handler = getHandler(null);
            }
            final Request request = new Request(method, args);
            final Request parentRequest = ServerHandler.threadLocal.get();
            try {
                Class.forName("io.seata.core.context.RootContext");
                final String xid = RootContext.getXID();
                request.setXid(xid);
            } catch (ClassNotFoundException ignore) {
            }
            if (parentRequest != null) {
                request.setParentRequestId(parentRequest.getRequestId());
                request.setTrace(parentRequest.isTrace());
            }
            return handler.senRequest(request).get();
        }

        public ClientHandler getHandler(Integer nodeId) {
            final RpcApi rpcApi = target.getAnnotation(RpcApi.class);
            final ConnectionManager connectionManager = ConnectionManager.getInstance();
            final String name = RpcClient.resolverValue(rpcApi.value(), environment);
            final String url = RpcClient.resolverValue(rpcApi.url(), environment);
            ClientHandler handler;
            if (StringUtils.isNotBlank(url)) {
                final String[] split = url.split(":");
                handler = connectionManager.choiceOneHandler(split[0], Integer.parseInt(split[1]));
            } else if (StringUtils.isNotBlank(name)) {
                if (nodeId != null) {
                    handler = connectionManager.choiceOneHandler(nodeId);
                } else {
                    handler = connectionManager.choiceOneHandler(name);
                }
            } else {
                throw new RpcRemoteApiException("not definition server provider");
            }
            if (handler == null) {
                throw new RpcRemoteApiException("can not find server provider");
            }
            return handler;
        }
    }
}
