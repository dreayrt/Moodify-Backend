package com.laphuth.moodify.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO ánh xạ dữ liệu JSON mà SePay gửi về qua Webhook khi có giao dịch tiền vào.
 * Tài liệu tham chiếu: https://my.sepay.vn/docs/webhook
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SepayWebhookDto {

    private Long id;                  // ID giao dịch trên SePay
    private String gateway;           // Tên ngân hàng (ví dụ: MBBank)
    private String transactionDate;   // Thời gian giao dịch (yyyy-MM-dd HH:mm:ss)
    private String accountNumber;     // Số tài khoản nhận
    private String subAccount;        // Tài khoản phụ (nếu có)
    private String code;              // Mã giao dịch SePay
    private String content;           // Nội dung chuyển khoản (chứa mã đơn hàng)
    private String transferType;      // "in" = tiền vào, "out" = tiền ra
    private Double transferAmount;    // Số tiền giao dịch
    private Double accumulated;       // Số dư luỹ kế
    private String referenceCode;     // Mã tham chiếu ngân hàng
    private String description;       // Mô tả bổ sung

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGateway() { return gateway; }
    public void setGateway(String gateway) { this.gateway = gateway; }

    public String getTransactionDate() { return transactionDate; }
    public void setTransactionDate(String transactionDate) { this.transactionDate = transactionDate; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getSubAccount() { return subAccount; }
    public void setSubAccount(String subAccount) { this.subAccount = subAccount; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTransferType() { return transferType; }
    public void setTransferType(String transferType) { this.transferType = transferType; }

    public Double getTransferAmount() { return transferAmount; }
    public void setTransferAmount(Double transferAmount) { this.transferAmount = transferAmount; }

    public Double getAccumulated() { return accumulated; }
    public void setAccumulated(Double accumulated) { this.accumulated = accumulated; }

    public String getReferenceCode() { return referenceCode; }
    public void setReferenceCode(String referenceCode) { this.referenceCode = referenceCode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public String toString() {
        return "SepayWebhookDto{" +
            "id=" + id +
            ", gateway='" + gateway + '\'' +
            ", content='" + content + '\'' +
            ", transferType='" + transferType + '\'' +
            ", transferAmount=" + transferAmount +
            ", referenceCode='" + referenceCode + '\'' +
            '}';
    }
}
