package io.basquiat.domain.member.service;

import io.basquiat.domain.member.model.Member;
import io.basquiat.domain.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public Mono<Member> createMember(String uid) {
        return memberRepository.save(new Member(uid));
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
                    existing.setUid(uid); // 단일 필드라 업데이트 의미는 없음
                    return memberRepository.save(existing);
                });
    }

    public Mono<Void> deleteMember(String uid) {
        return memberRepository.deleteById(uid);
    }
}