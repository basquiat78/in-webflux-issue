# Drain & Burst Test

# 테스트 환경

```aiignore
Language: Java21
DB: PostgreSQL 14.18
IDE: Intellij
OS: Apple M2 Sequoia 15.6.1
```

# 기본적인 구성

`Member`라는 객체 하나만 가지고 기본적인 `CRUD`를 생성해 심플하게 테스트를 진행해 볼려고 한다.

## 상황 재현을 하기 위한 Mockup Test

일단 디비에 넣는 것보다

```java

@BeforeEach
void setUp() {
	Mockito.when(memberRepository.save(Mockito.any(Member.class)))
			.thenAnswer(invocation -> {
				atomicInteger.incrementAndGet();
				Member member = new Member("uid-" + invocation.getArgument(0));
				return Mono.delay(Duration.ofMillis(100))
						.thenReturn(member);
			});
	startTime = System.currentTimeMillis();
}
```

`save` 함수를 호출 할때 `10ms` 정도의 딜레이를 주고 반환하도록만 `Mockito`를 사용해 `Mockup Test`를 진행한다.

```java
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
					Member member = new Member("uid-" + invocation.getArgument(0));
					return Mono.delay(Duration.ofMillis(10))
							.thenReturn(member);
				});
		startTime = System.currentTimeMillis();
	}

	@Test
	void reproduceDrainAndBurst() {
		int TOTAL_REQUESTS = 10;
		Flux<String> memberCreationFlux = Flux.range(1, TOTAL_REQUESTS)
				.map(i -> "uid-" + i)
				.log("Producer");
		// flatMap의 기본값은 둘다 256
		int concurrency = 256;
		int prefetch = 256;
		memberCreationFlux
				.flatMap(uid -> memberService.createMember(uid)
						.doOnSuccess(m -> {
							long elapsed = System.currentTimeMillis() - startTime;
							// 로그를 통해 drain 현상 확인
							System.out.printf("[Processed] %s | Total: %d | Time: %dms%n",
									m.getUid(), atomicInteger.get(), elapsed);
						}), concurrency, prefetch) // 병렬성 제한
				.log("Consumer") // flatMap 이후 로그
				.blockLast(Duration.ofSeconds(3000)); // 테스트가 완료될 때까지 블로킹 (실제 환경에서는 권장되지 않음)

		System.out.println("--- Test Finished ---");
		System.out.println("Total save calls: " + atomicInteger.get());
	}
}
```

10만건의 요청이 `producer`를 통해서 넘어오면 `consumer`에서 디비에 생성하는 로직을 돌리는 간단한 예제이다.

일반적으로 `flatMap`속성에 `concurrency`, `prefetch`이 존재하는데 `Buffer Size`와 처리할 양을 정하는 값이다.
아무런 옵션을 주지 않는다면 기본적으로 둘다 256으로 잡혀져 있다.

해당 내용은 패키지 `reactor.util.concurrent`에 있는 `Queues.class`에서 확인이 가능하다.

```java
public static final int XS_BUFFER_SIZE = Math.max(8,
		Integer.parseInt(System.getProperty("reactor.bufferSize.x", "32")));
/**
 * A small default of available slots in a given container, compromise between intensive pipelines, small
 * subscribers numbers and memory use.
 */
public static final int SMALL_BUFFER_SIZE = Math.max(16,
		Integer.parseInt(System.getProperty("reactor.bufferSize.small", "256")));
```

`SMALL_BUFFER_SIZE`가 기본값

`concurrency`가 1인 경우에는 웹플럭스에서는 `concatMap`을 사용할 수 있다.
그래서 비동기 작업을 동시에 몇개의 작업을 수행할 지 결정하는 옵션값이다.

`prefetch`는 `AMQP`나 `Redis`를 사용해 보면 볼 수 있는 옵션으로 보통 작업당 몇 개를 끌어와서 할 것인가에 대한 값을 정의한다.

아무튼 간단하게 위 코드를 설명하자면 실행되는 시간을 측정하기 위해 로그에서 찍기 위함이다.
이걸 통해서 짧은 순간에 `drain` 또는 `burst`를 확인한다.

마지막 `blockLast`메소드는 아무래도 테스트 로그를 확인하기 위해 테스트에서만 사용한다.

# 로그 확인시 주요 포인트 체크

로그에서 다음과 같은 행을 체크해 보면 된다.

```
2025-12-12T13:20:45.823+09:00  INFO 60841 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | request(18)
```

여기서 보면 `Producer`가 `request(18)`처럼 18개의 요청 처리를 하고 `Consumer`측에서 그 수만큼 동시에 18개를 처리하는 것을 확인하면 되는 것이 포인트!

## 일단 요청 건수를 작게 해서 하나씩 테스트 해본다.

지금 기존 코드에서는 `TOTAL_REQUEST`를 10으로 세팅했다.
그렇다면 순차적으로 한개씩 처리할 것이라고 생각하지만 실제 로그를 찍어보면

```
025-12-12T13:16:28.593+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] i.basquiat.BackpressureBufferDrainTest   : Started BackpressureBufferDrainTest in 1.494 seconds (process running for 2.039)
2025-12-12T13:16:28.613+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onSubscribe([Fuseable] FluxMapFuseable.MapFuseableSubscriber)
2025-12-12T13:16:28.614+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Consumer                                 : onSubscribe(FluxFlatMap.FlatMapMain)
2025-12-12T13:16:28.614+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Consumer                                 : request(unbounded)
2025-12-12T13:16:28.614+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | request(256)
2025-12-12T13:16:28.615+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-1)
2025-12-12T13:16:28.619+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-2)
2025-12-12T13:16:28.619+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-3)
2025-12-12T13:16:28.619+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-4)
2025-12-12T13:16:28.619+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-5)
2025-12-12T13:16:28.620+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-6)
2025-12-12T13:16:28.620+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-7)
2025-12-12T13:16:28.620+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-8)
2025-12-12T13:16:28.620+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-9)
2025-12-12T13:16:28.620+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onNext(uid-10)
2025-12-12T13:16:28.620+09:00  INFO 57466 --- [basquiat-web-flux-issue] [           main] Producer                                 : | onComplete()
[Processed] uid-Member(uid=uid-8) | Total: 10 | Time: 21ms
[Processed] uid-Member(uid=uid-7) | Total: 10 | Time: 21ms
2025-12-12T13:16:28.631+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-9] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-8)))
[Processed] uid-Member(uid=uid-1) | Total: 10 | Time: 21ms
[Processed] uid-Member(uid=uid-2) | Total: 10 | Time: 21ms
2025-12-12T13:16:28.631+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-2] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-1)))
[Processed] uid-Member(uid=uid-4) | Total: 10 | Time: 22ms
[Processed] uid-Member(uid=uid-9) | Total: 10 | Time: 22ms
[Processed] uid-Member(uid=uid-6) | Total: 10 | Time: 22ms
[Processed] uid-Member(uid=uid-3) | Total: 10 | Time: 22ms
[Processed] uid-Member(uid=uid-10) | Total: 10 | Time: 22ms
[Processed] uid-Member(uid=uid-5) | Total: 10 | Time: 22ms
2025-12-12T13:16:28.632+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-5] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-4)))
2025-12-12T13:16:28.632+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-3)))
2025-12-12T13:16:28.632+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-5)))
2025-12-12T13:16:28.633+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-6)))
2025-12-12T13:16:28.633+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-7)))
2025-12-12T13:16:28.633+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-9)))
2025-12-12T13:16:28.633+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-10)))
2025-12-12T13:16:28.633+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-2)))
2025-12-12T13:16:28.633+09:00  INFO 57466 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onComplete()
--- Test Finished ---
Total save calls: 10
```

처음 `Consumer`쪽에 `: request(unbounded)`찍히면서 무제한으로 처리하겠다는 신호를 날린 이후 `concurrency`를 256으로 잡았기에 `Producer`에선 `request(256)`
이라는 로그가 남았다.
하지만 수가 작다 보니 한번에 끝.

하지만 전체 로그를 확인하면 2건, 2건, 6건이 마치 한번에 실행되는 것을 확인할 수 있다.
외부에서 본다면 작지만 `burst`가 발생한 것 처럼 보인다.

그렇다면 이제 10만 건으로 늘려서 테스트 해보자.

```
.
.
.
[Processed] uid-Member(uid=uid-97431) | Total: 97664 | Time: 5369ms
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | onNext(uid-97666)
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97431)))
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | request(1)
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | onNext(uid-97667)
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | request(1)
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | onNext(uid-97668)
[Processed] uid-Member(uid=uid-97433) | Total: 97667 | Time: 5369ms
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97433)))
[Processed] uid-Member(uid=uid-97242) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97252) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97262) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97272) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97282) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97292) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97302) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97312) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97322) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97332) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97342) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97352) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97362) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97372) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97382) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97392) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97402) | Total: 97668 | Time: 5369ms
[Processed] uid-Member(uid=uid-97412) | Total: 97668 | Time: 5370ms
[Processed] uid-Member(uid=uid-97422) | Total: 97668 | Time: 5370ms
[Processed] uid-Member(uid=uid-97432) | Total: 97668 | Time: 5370ms
2025-12-12T13:07:09.117+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | request(1)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97669)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97432)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97242)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97252)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97412)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97262)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97272)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97282)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97292)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97302)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97312)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97322)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97332)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97342)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97352)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97362)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97422)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97372)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97382)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97392)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97402)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | request(20)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97670)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97671)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97672)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97673)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97674)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97675)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97676)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97677)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97678)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97679)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97680)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97681)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97682)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97683)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97684)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97685)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97686)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97687)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97688)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-97689)
[Processed] uid-Member(uid=uid-97434) | Total: 97689 | Time: 5370ms
[Processed] uid-Member(uid=uid-97436) | Total: 97689 | Time: 5370ms
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-5] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97434)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-5] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97436)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | request(1)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | onNext(uid-97690)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | request(1)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | onNext(uid-97691)
[Processed] uid-Member(uid=uid-97435) | Total: 97691 | Time: 5370ms
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97435)))
[Processed] uid-Member(uid=uid-97439) | Total: 97691 | Time: 5370ms
[Processed] uid-Member(uid=uid-97438) | Total: 97691 | Time: 5370ms
[Processed] uid-Member(uid=uid-97437) | Total: 97691 | Time: 5370ms
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | request(1)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-97692)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97437)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97438)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97439)))
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | request(3)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-97693)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-97694)
2025-12-12T13:07:09.118+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-97695)
[Processed] uid-Member(uid=uid-97443) | Total: 97695 | Time: 5371ms
[Processed] uid-Member(uid=uid-97440) | Total: 97695 | Time: 5371ms
[Processed] uid-Member(uid=uid-97445) | Total: 97695 | Time: 5371ms
[Processed] uid-Member(uid=uid-97446) | Total: 97695 | Time: 5371ms
[Processed] uid-Member(uid=uid-97441) | Total: 97695 | Time: 5371ms
2025-12-12T13:07:09.119+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-97443)))
[Processed] uid-Member(uid=uid-97444) | Total: 97695 | Time: 5371ms
[Processed] uid-Member(uid=uid-97442) | Total: 97695 | Time: 5371ms
.
.
.

2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99872)))
[Processed] uid-Member(uid=uid-99882) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99882)))
[Processed] uid-Member(uid=uid-99892) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99892)))
[Processed] uid-Member(uid=uid-99902) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99902)))
[Processed] uid-Member(uid=uid-99912) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99912)))
[Processed] uid-Member(uid=uid-99922) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99922)))
[Processed] uid-Member(uid=uid-99932) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99932)))
[Processed] uid-Member(uid=uid-99942) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99942)))
[Processed] uid-Member(uid=uid-99952) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99952)))
[Processed] uid-Member(uid=uid-99962) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99962)))
[Processed] uid-Member(uid=uid-99972) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99972)))
[Processed] uid-Member(uid=uid-99982) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99982)))
[Processed] uid-Member(uid=uid-99992) | Total: 100000 | Time: 5503ms
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-99992)))
2025-12-12T13:07:09.251+09:00  INFO 50165 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onComplete()
--- Test Finished ---
Total save calls: 100000
```

건수가 많아지면 한개식 진행되다가도 중간에 다량의 데이터가 마치 한번에 디비에 쓰여지는 것 같은 행위가 보인다.

## prefetch를 1000으로 늘린다면 어떨까?

요청 건수를 10개로 처음 시도하면 한번에 10개가 동시에 `burst`가 발생하기도 하고 한 건씩 처리하다가 2, 3개씩 묶여서 처리되기도 한다.

그리고 요청 건수를 10만건으로 하면 확실히 `prefetch`가 작동하면서 처리 속도가 빨라진다.

## 그렇다면 prefetch는 기본값으로 두고 concurrency를 조절하면 어떻게 될까?

`concurrency`를 10으로 한번 세팅하고 실행하게 되면

```
2025-12-12T13:33:30.667+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | request(3)
2025-12-12T13:33:30.667+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-29496)
2025-12-12T13:33:30.667+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-29497)
2025-12-12T13:33:30.667+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-29498)
2025-12-12T13:33:30.667+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | request(1)
2025-12-12T13:33:30.667+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-6] Producer                                 : | onNext(uid-29499)
[Processed] uid-Member(uid=uid-29490) | Total: 29499 | Time: 34649ms
[Processed] uid-Member(uid=uid-29491) | Total: 29499 | Time: 34649ms
2025-12-12T13:33:30.670+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29490)))
2025-12-12T13:33:30.670+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29491)))
2025-12-12T13:33:30.670+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | request(1)
2025-12-12T13:33:30.670+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | onNext(uid-29500)
2025-12-12T13:33:30.670+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | request(1)
2025-12-12T13:33:30.670+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | onNext(uid-29501)
[Processed] uid-Member(uid=uid-29492) | Total: 29501 | Time: 34654ms
2025-12-12T13:33:30.675+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29492)))
2025-12-12T13:33:30.675+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | request(1)
2025-12-12T13:33:30.675+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | onNext(uid-29502)
[Processed] uid-Member(uid=uid-29493) | Total: 29501 | Time: 34654ms
2025-12-12T13:33:30.675+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-3] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29493)))
2025-12-12T13:33:30.675+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | request(1)
2025-12-12T13:33:30.675+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-3] Producer                                 : | onNext(uid-29503)
[Processed] uid-Member(uid=uid-29494) | Total: 29503 | Time: 34655ms
[Processed] uid-Member(uid=uid-29495) | Total: 29503 | Time: 34655ms
2025-12-12T13:33:30.676+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-5] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29494)))
2025-12-12T13:33:30.676+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-5] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29495)))
2025-12-12T13:33:30.676+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | request(1)
2025-12-12T13:33:30.676+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | onNext(uid-29504)
2025-12-12T13:33:30.676+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | request(1)
2025-12-12T13:33:30.676+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-5] Producer                                 : | onNext(uid-29505)
[Processed] uid-Member(uid=uid-29498) | Total: 29505 | Time: 34656ms
2025-12-12T13:33:30.677+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-9] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29498)))
2025-12-12T13:33:30.677+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-9] Producer                                 : | request(1)
2025-12-12T13:33:30.677+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-9] Producer                                 : | onNext(uid-29506)
[Processed] uid-Member(uid=uid-29497) | Total: 29506 | Time: 34658ms
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-8] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29497)))
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-8] Producer                                 : | request(1)
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-8] Producer                                 : | onNext(uid-29507)
[Processed] uid-Member(uid=uid-29496) | Total: 29507 | Time: 34658ms
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-7] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29496)))
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-7] Producer                                 : | request(1)
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-7] Producer                                 : | onNext(uid-29508)
[Processed] uid-Member(uid=uid-29499) | Total: 29507 | Time: 34658ms
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-7] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29499)))
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-7] Producer                                 : | request(1)
2025-12-12T13:33:30.679+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-7] Producer                                 : | onNext(uid-29509)
[Processed] uid-Member(uid=uid-29500) | Total: 29509 | Time: 34662ms
[Processed] uid-Member(uid=uid-29501) | Total: 29509 | Time: 34662ms
2025-12-12T13:33:30.683+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29500)))
2025-12-12T13:33:30.683+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29501)))
2025-12-12T13:33:30.683+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | request(1)
2025-12-12T13:33:30.683+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | onNext(uid-29510)
2025-12-12T13:33:30.683+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | request(1)
2025-12-12T13:33:30.683+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-1] Producer                                 : | onNext(uid-29511)
[Processed] uid-Member(uid=uid-29503) | Total: 29511 | Time: 34665ms
2025-12-12T13:33:30.686+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-4] Consumer                                 : onNext(Member(uid=uid-Member(uid=uid-29503)))
2025-12-12T13:33:30.686+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | request(1)
2025-12-12T13:33:30.686+09:00  INFO 70374 --- [basquiat-web-flux-issue] [     parallel-4] Producer                                 : | onNext(uid-29512)
[Processed] uid-Member(uid=uid-29502) | Total: 29512 | Time: 34667ms
[Processed] uid-Member(uid=uid-29505) | Total: 29512 | Time: 34667ms
```

`Producer`의 요청 건수가 그 사이를 왔다갔다 한다.
나의 환경에서는 대부분 `request(1)`처럼 1에서 7사이를 왔다갔다 하기도 하고 가끔 10이 터지기도 한다.

## 만일 둘다 1000으로 두면????

여러 번 돌린 결과 최대 `Producer`측에서 `request(130)`까지 요청하고 `Consumer`에서 130개를 동시에 처리하는 것처럼 보이는 현상이 있다.

## 이에 대한 짧은 분석

일단 나는 로그만을 본다면 웹플럭스 정확히 말한다면 `reactor`의 정상적인 특징이라고 본다.
왜냐하면 생산자인 `Producer`와 소비자인 `Consumer` 사이의 속도의 차이를 관리하고 있다는 관점에서 보면 지극히 정상적인 현상이 아닐까?

거기에 `concurrency`와 `prefetch`의 옵션값에 따라서 10만건이 금방 처리되기도 한다.

## 하지만 만일 실제 DB와 인터랙션중에 이런 현상이 발생한다면??

먼저 `Member`객체를 변경하자.

이럴거면 그냥 자동 아이디 생성으로 할껄...

간과한 점이 자동증가 아이디가 아닌 외부로부터 생성한 정보를 아이디로 사용할 경우 `save`가 생각과는 다르게 작동한 다는 것을 잊고 있었다.

```java
package io.basquiat.domain.member.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "member")
public class Member implements Persistable<String> {
	@Id
	private String uid;

	@Transient
	private boolean isCreateMember;

	@Override
	public String getId() {
		return this.uid;
	}

	@Override
	public boolean isNew() {
		return this.isCreateMember;
	}
}
```

이렇게 변경을 한다.

```java
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
```

변경된 것은 실제 디비에 넣는 작업으로 변경했다.

이를 통해 `drain and burst` 현상을 보려고 한다.

해당 관련 테스트시에는 `application.yml`에서

```yml
logging:
  level:
    root: INFO
    org.springframework.r2dbc: DEBUG
    # Pool 관련 로그 확인용
    #io.r2dbc.pool: DEBUG
    #io.r2dbc.postgresql.QUERY: DEBUG
    #io.r2dbc.postgresql.PARAM: DEBUG
```

요렿게 저 부분은 주석을 처리하고 확인해 보자.

실제로 이 코드를 여러 번 실행해서 로그상에서 `Producer`의 `request(*)`를 찾아서 `drain`이 발생하고 한번에 디비에 데이터를 생성하는 부분을 찾아가 보자.

나의 환경내에서는 최대 `request(140)`정도가 찍힌다.

요청 건수를 100만건으로도 올려서 꽤 오랫동안 테스트를 해봤지만 한 번에 1000건이 `busrt after drain`이 되는 현상을 경험하지 못했다.

# 재현 실패

어떻게 구성되어 있는지 확인할 수 없어 일단은 재현 실패를 했다.

다만 몇 가지 인사이트를 공유하자면 다음과 같다.

# DB Write시 부하 문제에 대한 고민

## concurrency, prefetch 최적화 값을 찾는다.

일단 `concurrency`의 최적화된 값을 찾아야 한다.

이 값들 예를 들면 `concurrency`를 포함한 `prefetch` 사이즈를 크게 잡는다면 속도면에서는 확실히 빠르다.

하지만 DB와 관련되어 있다면 이를 통해서 웹플럭스가 동작을 할 때 처리 용량을 초과할 때 벌어지는 현상을 방지해야 한다고 생각한다.

만일 DB 커넥션 풀이 몇개인지 몇개로 설정을 했는냐에 따라 이 값도 조절된다.

결국 속도보다는 안정적인 운영을 생각한다면 최소한 세팅된 DB 커넥션 풀보다 작게 설정하거나 보수적으로 잡아서 같은 값으로 세팅하는게 중요하지 않을까?

두 번째는 `prefetch`설정값인데 이것도 값이 크면 클수록 `drain`의 크기가 증가 할 수 있다고 판단이 된다.

따라서 최적화된 값이 얼마인지 알 수 없지만 DB와 관련되어 있다면 최소한은 DB의 `batch size`에 맞춰서 잡는 방법도 고려해 볼 수 있다.

많이들 사용하고 있는 `MySql`, `PostGreSQL`과 관련해서 조사해 보면 공통적으로 50개에서 100 `row` 정도를 권장하고 있다.

하지만 공식 사이트를 뒤져봐도 이와 관련 최적화된 사이즈는 찾을 수 없었다.

~~능력 부족???~~

뭐 용량에 따라서 무리가 없는 값을 찾는 것이 관건.

## 코드레벨에서 해볼 만 한 것

디비 작업에 무리를 준다고 판단이 된다면 예전 JPA 코드를 웹플럭스 위에서 사용할 때 스케쥴을 따로 Wrapper로 분리해서 해당 래퍼로 감싸서 사용한 방법을 떠올 려 볼 수 있을 거 같다.

예를 들면 `R2DBC`관련 라이브러리가 나오기 전에 많이들 사용하던 방식이 다음과 같다.

```java

@Service
public class AsyncService {

	@Autowired
	@Qualifier("jdbcScheduler")
	private Scheduler jdbcScheduler;

	/**
	 * sync -> async using
	 * @param <T>
	 * @param callable
	 * @return Mono<T>
	 */
	public <T> Mono<T> excute(Callable<T> callable) {
		return Mono.subscriberContext()
				.flatMap(context -> {
					return Mono.fromCallable(callable).subscribeOn(Schedulers.elastic()).publishOn(Schedulers.elastic());
				});
	}

	/**
	 * sync -> async using for JDBC
	 * @param <T>
	 * @param callable
	 * @return Mono<T>
	 */
	public <T> Mono<T> excuteJDBC(Callable<T> callable) {
		return Mono.subscriberContext()
				.flatMap(context -> {
					return Mono.fromCallable(callable).subscribeOn(Schedulers.parallel()).publishOn(jdbcScheduler);
				};
	}

	/**
	 * 동기식 api를 비동기 호출로 변환한다.
	 * @param <T>
	 * @param callable
	 * @return CompletableFuture<T>
	 */
	public <T> CompletableFuture<T> excuteCF(Supplier<T> callable) {
		return CompletableFuture.supplyAsync(callable);
	}

}
```

여기서 관건은 `subscribeOn(Schedulers.parallel())` 이 부분이다.

```java
package io.basquiat;

import io.basquiat.domain.member.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class BackpressureBufferDrainWithSchedulerTest {

	private final AtomicInteger atomicInteger = new AtomicInteger(0);

	// parallelism은 일반적인 디비 커넥션 풀을 20개로 잡는 것을 고려해서 20개로 일단 잡아보자.
	// 이 값은 세팅값에 따라서 최소 세팅값이거나 그 이하로 잡도록 해보자.
	private final Scheduler writeDbScheduler = Schedulers.newParallel("write-db-scheduler", 20);

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
							.subscribeOn(writeDbScheduler)
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
```

이렇게 분리된 스케줄러를 사용하도록 하자.

일단 따로 분리된 스케줄러를 사용하고 있는지 로그를 찍기 위해서

기존에 디비에 생성하는 메소드에 로그를 찍도록 수정한다.

```java

@Service
@AllArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;

	public Mono<Member> createMember(String uid) {
		Member createMember = Member.builder()
				.uid(uid)
				.isCreateMember(true)
				.build();
		return memberRepository.save(createMember).doOnSubscribe(subscription -> {
			String threadName = Thread.currentThread().getName();
			System.out.println("[Scheduler Name] : " + threadName);
		});
	}

	public Mono<Member> getMember(String uid) {
		return memberRepository.findById(uid);
	}

	public Flux<Member> getAllMembers() {
		return memberRepository.findAll();
	}

	public Mono<Member> updateMember(String uid) {
		return memberRepository.findById(uid)
				.flatMap(existing -> {
					existing.setUid(uid);
					return memberRepository.save(existing);
				});
	}

	public Mono<Void> deleteMember(String uid) {
		return memberRepository.deleteById(uid);
	}

}
```

이렇게 하면 실제 `db write`시점에 사용되는 분리된 스케줄러를 사용하는 것을 확인할 수 있다.

## 추가적으로

한건씩 생성하는게 부담이라고 한다면 `buffer`를 이용해서 한번에 몇 건씩 넣도록 하는것도 좋은 방법이 아닐까?

# At a Glance

최대한 설명을 잘 하고 싶은데 이게 잘 되었는지는 모르겠지만 아는 한도내에서 최대한 노력을 해봤다고 말하고 싶다...