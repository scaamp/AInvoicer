namespace customer.cap_java;

using { cuid, managed } from '@sap/cds/common';

entity KsefDocuments : cuid, managed {
    fileName : String(255);
    mimeType : String(100) @Core.IsMediaType;
    content : LargeBinary
    @Core.MediaType: mimeType
    @Core.ContentDisposition.Filename: fileName;
    sha256 : String(64);
}