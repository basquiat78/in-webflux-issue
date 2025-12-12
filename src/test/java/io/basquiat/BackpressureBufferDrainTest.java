package io.basquiat;

import io.basquiat.domain.member.model.Member;
import io.basquiat.domain.member.repository.MemberRepository;
import io.basquiat.domain.member.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class BackpressureBufferDrainTest {

	private final AtomicInteger atomicInteger = new AtomicInteger(0);

	@Autowired
	private MemberService memberService;

	@MockitoBean
	private MemberRepository memberRepository;

	private long startTime;

	@BeforeEach
	void setUp() {
		Mockito.when(memberRepository.save(Mockito.any(Member.class)))
				.thenAnswer(invocation -> {
					atomicInteger.incrementAndGet();
					Member member = new Member("uid-" + invocation.getArgument(0), true);
					return Mono.delay(Duration.ofMillis(10))
							.thenReturn(member);
				});
		startTime = System.currentTimeMillis();
	}

	@Test
	void reproduceDrainAndBurst() {
		int TOTAL_REQUESTS = 100_000;
		Flux<String> memberCreationFlux = Flux.range(1, TOTAL_REQUESTS)
				.map(i -> "uid-" + i)
				.log("Producer");
		// flatMap의 기본값은 둘다 256
		int concurrency = 1000;
		int prefetch = 1000;
		memberCreationFlux
				.flatMap(uid -> memberService.createMember(uid)
						.doOnSuccess(m -> {
							long elapsed = System.currentTimeMillis() - startTime;
							System.out.printf("[Processed] %s | Total: %d | Time: %dms%n",
									m.getUid(), atomicInteger.get(), elapsed);
						}), concurrency, prefetch)
				.log("Consumer")
				.blockLast(Duration.ofSeconds(3000));

		System.out.println("--- Test Finished ---");
		System.out.println("Total save calls: " + atomicInteger.get());
	}
}