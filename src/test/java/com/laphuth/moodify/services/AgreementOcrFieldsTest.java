package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.contract.AgreementDetails;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgreementOcrFieldsTest {
    @Test
    void readsBothPartiesAndKeepsPlannedSigningDateSeparateFromTheLicenseDates() {
        AgreementDetails result = AgreementOcrFields.parse("""
            Tên bên cấp quyền: Hồ sơ A
            Địa chỉ bên cấp quyền: Thành phố Hồ Chí Minh, Việt Nam
            Người đại diện bên cấp quyền: Lâm Phước Thuận
            Chức vụ bên cấp quyền: Người quản lý nội dung
            Tên bên nhận quyền: Hồ sơ B
            Địa chỉ bên nhận quyền: Thành phố Hồ Chí Minh, Việt Nam
            Người đại diện bên nhận quyền: Nguyễn Khánh Duy
            Chức vụ bên nhận quyền: Quản trị viên
            Ngày bắt đầu: 10/11/2026
            Ngày hết hạn: 09/11/2027
            Địa điểm dự kiến ký: Thành phố Hồ Chí Minh
            Ngày dự kiến ký: 16/11/2026
            """);
        assertEquals(new AgreementDetails("Hồ sơ A", "Thành phố Hồ Chí Minh, Việt Nam", "Lâm Phước Thuận", "Người quản lý nội dung",
            "Hồ sơ B", "Thành phố Hồ Chí Minh, Việt Nam", "Nguyễn Khánh Duy", "Quản trị viên", "Thành phố Hồ Chí Minh", "2026-11-16"), result);
    }

    @Test
    void doesNotGuessFromEmptyConflictingOrInvalidFields() {
        assertNull(AgreementOcrFields.parse("""
            Tên bên cấp quyền:
            Tên pháp lý
            Tên bên nhận quyền: Một bên
            Tên bên nhận quyền: Bên khác
            Địa chỉ bên cấp quyền: ____
            Ngày dự kiến ký: 31/02/2026
            Ngày bắt đầu: 10/11/2026
            """));
    }

    @Test
    void validatesManualSigningDatesAndAcceptsLegacyUploads() {
        assertNull(AgreementDetails.fromJson(null));
        assertNull(AgreementDetails.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> AgreementDetails.fromJson("""
            {"plannedSigningDate":"2026-02-31"}
            """));
    }
}
