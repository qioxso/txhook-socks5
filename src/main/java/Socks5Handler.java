import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FutureListener;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

class Socks5Handler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5InitialRequest msg) {
        // 阶段 1: 握手 (保持不变)
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
        ctx.pipeline().remove(this);
        ctx.pipeline().remove(Socks5InitialRequestDecoder.class);
        ctx.pipeline().addLast(new Socks5CommandRequestDecoder());
        ctx.pipeline().addLast(new Socks5CommandRequestHandler());
    }
}

class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {

    // 定义你需要监听的域名列表
    private static final List<String> WATCH_LIST = Arrays.asList("msfwifi.3g.qq.com", "example.com", "www.google.com");

    @Override
    protected void channelRead0(final ChannelHandlerContext clientCtx, DefaultSocks5CommandRequest msg) {
        if (!msg.type().equals(Socks5CommandType.CONNECT)) {
            clientCtx.close();
            return;
        }

        String targetHost = msg.dstAddr();
        int targetPort = msg.dstPort();
        System.out.println(">> 客户端请求连接: " + targetHost + ":" + targetPort);

        // 检查是否是我们需要监控的域名
        boolean isWatched = WATCH_LIST.stream().anyMatch(targetHost::contains);

        Bootstrap b = new Bootstrap();
        b.group(clientCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 【关键点 1】: 如果是监控域名，在目标服务器的 Pipeline 中添加日志处理器
                        // 这里记录的是：目标服务器发回给客户端的数据 (Response)
                        if (isWatched) {
                            ch.pipeline().addLast(new TrafficLoggingHandler(targetHost, "响应 (Server -> Client)"));
                        }
                        ch.pipeline().addLast(new RelayHandler(clientCtx.channel()));
                    }
                });

        b.connect(targetHost, targetPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel targetChannel = future.channel();

                clientCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                        Socks5CommandStatus.SUCCESS,
                        msg.dstAddrType(),
                        msg.dstAddr(),
                        msg.dstPort()));

                // 清理 pipeline
                clientCtx.pipeline().remove(Socks5ServerEncoder.class);
                clientCtx.pipeline().remove(Socks5CommandRequestDecoder.class);
                clientCtx.pipeline().remove(this);

                // 【关键点 2】: 如果是监控域名，在客户端的 Pipeline 中添加日志处理器
                // 这里记录的是：客户端发给目标服务器的数据 (Request)
                if (isWatched) {
                    // 注意：必须加在 RelayHandler 之前
                    clientCtx.pipeline().addLast(new TrafficLoggingHandler(targetHost, "请求 (Client -> Server)"));
                }

                clientCtx.pipeline().addLast(new RelayHandler(targetChannel));
            } else {
                clientCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                        Socks5CommandStatus.FAILURE, msg.dstAddrType()));
                clientCtx.close();
            }
        });
    }
}

/**
 * 专门用于打印/保存流量的处理器
 */
class TrafficLoggingHandler extends ChannelInboundHandlerAdapter {
    private final String domain;
    private final String direction;

    public TrafficLoggingHandler(String domain, String direction) {
        this.domain = domain;
        this.direction = direction;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;

            // 1. 打印数据长度
            int readableBytes = buf.readableBytes();

            // 2. 获取内容 (注意：不要直接 read，那样会移动指针导致后续 RelayHandler 读不到数据)
            // 使用 toString(Charset) 会读取内容但不改变原来的 readerIndex，或者先 duplicate()
            // 为了安全起见，我们只打印前 1024 字节，避免日志爆炸
            String content = buf.toString(0, Math.min(readableBytes, 1024), StandardCharsets.UTF_8);

            // 3. 格式化输出
            System.out.println("========================================");
            System.out.println("Domain: " + domain);
            System.out.println("Direction: " + direction);
            System.out.println("Size: " + readableBytes + " bytes");
            System.out.println("Content (UTF-8 Preview):");

            // 如果是明显乱码（比如 HTTPS），打印 Hex 可能会更好，这里做个简单的判断
            if (isLikelyBinary(content)) {
                System.out.println("[加密数据或二进制数据，打印 Hex]");
                // 直接传入 readableBytes 作为长度，不再取最小值
                System.out.println(ByteBufUtil.hexDump(buf, 0, readableBytes));
                //System.out.println(ByteBufUtil.hexDump(buf, 0, Math.min(readableBytes, 2048))); // 仅打印前128字节Hex
            } else {
                System.out.println(content);
            }
            System.out.println("========================================\n");

            // TODO: 如果你想保存到文件，可以在这里调用文件写入逻辑
            // FileUtils.append(domain + ".log", content);
        }

        // 4. 必须把消息传递给下一个 Handler (即 RelayHandler)，否则流量会断掉
        ctx.fireChannelRead(msg);
    }

    // 简单的判断是否包含大量不可见字符，用于区分 HTTP 和 HTTPS/Binary
    private boolean isLikelyBinary(String text) {
        int binaryChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) {
                binaryChars++;
            }
        }
        // 如果不可见字符超过 10%，认为是二进制/加密数据
        return text.length() > 0 && ((double) binaryChars / text.length() > 0.1);
    }
}

// RelayHandler 保持不变，负责纯粹的转发
class RelayHandler extends ChannelInboundHandlerAdapter {
    private final Channel relayChannel;

    public RelayHandler(Channel relayChannel) {
        this.relayChannel = relayChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (relayChannel.isActive()) {
            relayChannel.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (relayChannel.isActive()) {
            relayChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}

