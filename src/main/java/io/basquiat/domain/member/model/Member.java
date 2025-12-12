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
@Table("member")
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