package com.dpw.fos.adapter.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private final int max;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result = new CompletableFuture<>();
  private Subscription subscription;

  LimitedBodySubscriber(int max) {
    this.max = max;
  }

  public CompletionStage<byte[]> getBody() {
    return result;
  }

  public void onSubscribe(Subscription s) {
    subscription = s;
    s.request(1);
  }

  public void onNext(List<ByteBuffer> items) {
    for (ByteBuffer b : items) {
      if (b.remaining() > max - out.size()) {
        subscription.cancel();
        result.completeExceptionally(new IllegalStateException("Response too large"));
        return;
      }
      byte[] bytes = new byte[b.remaining()];
      b.get(bytes);
      out.writeBytes(bytes);
    }
    subscription.request(1);
  }

  public void onError(Throwable t) {
    result.completeExceptionally(t);
  }

  public void onComplete() {
    result.complete(out.toByteArray());
  }
}
