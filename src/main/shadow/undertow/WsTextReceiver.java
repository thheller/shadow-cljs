package shadow.undertow;

import clojure.lang.IFn;
import io.undertow.websockets.core.*;

import java.io.IOException;

// clojure doesn't like extending classes and proxy is weird
public class WsTextReceiver extends AbstractReceiveListener {

    // clojure fn to delegate to
    public final IFn callback;

    public WsTextReceiver(IFn callback) {
        this.callback = callback;
    }

    @Override
    protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
        callback.invoke(channel, message.getData());
    }

    @Override
    protected void onFullBinaryMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
        super.onFullBinaryMessage(channel, message);
        throw new IllegalStateException("FIXME: binary messages are not supported");
    }

    @Override
    protected void onCloseMessage(CloseMessage cm, WebSocketChannel channel) {
        callback.invoke(channel, null);
    }
}
