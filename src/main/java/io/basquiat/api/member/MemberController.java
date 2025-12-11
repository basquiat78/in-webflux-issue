package io.basquiat.api.member;

import io.basquiat.domain.member.model.Member;
import io.basquiat.domain.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    @PostMapping
    public Mono<Member> create(@RequestBody Member request) {
        return memberService.createMember(request.getUid());
    }

    @GetMapping("/{uid}")
    public Mono<Member> get(@PathVariable String uid) {
        return memberService.getMember(uid);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Member> getAll() {
        return memberService.getAllMembers();
    }

    @PutMapping("/{uid}")
    public Mono<Member> update(@PathVariable String uid) {
        return memberService.updateMember(uid);
    }

    @DeleteMapping("/{uid}")
    public Mono<Void> delete(@PathVariable String uid) {
        return memberService.deleteMember(uid);
    }
}