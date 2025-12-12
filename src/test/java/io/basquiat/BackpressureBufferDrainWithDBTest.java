package io.basquiat;

import io.basquiat.domain.member.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class BackpressureBufferDrainWithDBTest {

	private final AtomicInteger atomicInteger = new AtomicInteger(0);

	@Autowired
	private MemberService memberService;


	@Test
	void reproduceDrainAndBurstWithDB() {
		final long startTime = System.currentTimeMillis();
		int TOTAL_REQUESTS = 100_000;
		Flux<String> memberCreationFlux = Flux.range(1, TOTAL_REQUESTS)
				.map(i -> "uid-" + i)
				.log("Producer");
		int concurrency = 256;
		int prefetch = 256;

		memberCreationFlux
				.flatMap(uid -> {
					atomicInteger.incrementAndGet();
					return memberService.createMember(uid)
							.doOnSuccess(m -> {
								long elapsed = System.currentTimeMillis() - startTime;
								System.out.printf("[Processed] %s | Total: %d | Time: %dms%n",
										m.getUid(), atomicInteger.get(), elapsed);
							})
							.onErrorResume(e -> {
								System.err.printf("[Error] Failed to insert %s: %s%n", uid, e.getMessage());
								return Mono.empty();
							});
				}, concurrency, prefetch)
				.log("Consumer")
				.blockLast(Duration.ofSeconds(3000));

		System.out.println("--- Test Finished ---");
		System.out.println("Total processed attempts: " + atomicInteger.get());
	}
}