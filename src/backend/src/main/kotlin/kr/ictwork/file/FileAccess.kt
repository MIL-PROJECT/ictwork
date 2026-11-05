package kr.ictwork.file

import kr.ictwork.admin.Actor
import org.springframework.stereotype.Component

/**
 * 용도별 내려받기 권한 판정기 (slices/11 2.3.2). 업무(②)·양식(④-b)·특성(⑥)이 각자 구현을 등록한다
 * (결재 모듈이 대상 처리를 업무 쪽에 맡기는 것과 같은 방식, approval.ApprovalTargetHandler)
 */
interface FileAccessRule {
    val purposes: Set<String>

    /** 묶인 파일을 이 사람이 내려받을 수 있는가. 볼 수 없으면 거짓(404) */
    fun canDownload(actor: Actor, file: StoredFile): Boolean
}

/** 내려받기 권한: 임시 파일은 올린 사람만, 묶인 파일은 용도별 판정기가 본다. 화면과 관계없이 권한으로만 본다 (G-68) */
@Component
class FileAccess(private val rules: List<FileAccessRule>) {
    fun canDownload(actor: Actor, file: StoredFile): Boolean =
        if (!file.linked) {
            file.uploadedByAccountId == actor.account.id
        } else {
            rules.any { file.purpose in it.purposes && it.canDownload(actor, file) }
        }
}
