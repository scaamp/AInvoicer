// Zweryfikowany 2026-10-27 przez runtime $metadata aktywnego bindingu OData V4 H01.
// Model zawiera wyłącznie encje i pola potrzebne do read-only integracji.
@cds.external: true
@requires: 'internal-user'
service AinvoicerH01 {
  @cds.persistence.skip
  entity ZC_AINV_INV_H {
    key InvoiceUUID       : UUID;
        InvoiceNumber     : String(35);
        OriginProcess     : String(10);
        Direction         : String(10);
        InvoiceKind       : String(12);
        InvoiceStatus     : String(12);
        CompanyCode       : String(4);
        IssueDate         : Date;
        PostingDate       : Date;
        DueDate           : Date;
        SellerNip         : String(10);
        SellerName        : String(100);
        BuyerNip          : String(10);
        BuyerName         : String(100);
        NetAmount         : Decimal(17, 2);
        TaxAmount         : Decimal(17, 2);
        GrossAmount       : Decimal(17, 2);
        CurrencyCode      : String(5);
        LocalCreatedBy    : String(100);
        LocalCreatedAt    : Timestamp;
        LocalLastChangedBy: String(100);
        LocalLastChangedAt: Timestamp;
        LastChangedAt     : Timestamp;
        _Items            : Association to many ZC_AINV_INV_I
                              on _Items.InvoiceUUID = $self.InvoiceUUID;
        _References       : Association to many ZC_AINV_INV_REF
                              on _References.InvoiceUUID = $self.InvoiceUUID;
  }

  @cds.persistence.skip
  entity ZC_AINV_INV_I {
    key ItemUUID          : UUID;
        InvoiceUUID       : UUID;
        ItemNo            : String(6);
        MaterialId        : String(40);
        Description       : String(255);
        Quantity          : Decimal(15, 3);
        UnitCode          : String(3);
        UnitPrice         : Decimal(17, 2);
        NetAmount         : Decimal(17, 2);
        TaxRate           : Decimal(5, 2);
        TaxCategory       : String(8);
        TaxAmount         : Decimal(17, 2);
        GrossAmount       : Decimal(17, 2);
        CurrencyCode      : String(5);
        LocalCreatedBy    : String(100);
        LocalCreatedAt    : Timestamp;
        LocalLastChangedBy: String(100);
        LocalLastChangedAt: Timestamp;
        LastChangedAt     : Timestamp;
  }

  @cds.persistence.skip
  entity ZC_AINV_INV_REF {
    key RefUUID           : UUID;
        InvoiceUUID       : UUID;
        SourceSystem      : String(20);
        SourceModule      : String(5);
        ReferenceRole     : String(15);
        SourceDocNo       : String(35);
        SourceFiscalYear  : String(4);
        SourceItemNo      : String(6);
        LocalCreatedBy    : String(100);
        LocalCreatedAt    : Timestamp;
        LocalLastChangedBy: String(100);
        LocalLastChangedAt: Timestamp;
        LastChangedAt     : Timestamp;
  }
}
