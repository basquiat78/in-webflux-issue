package io.basquiat.domain.member.service;

import io.basquiat.domain.member.model.Member;
import io.basquiat.domain.member.repository.MemberRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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