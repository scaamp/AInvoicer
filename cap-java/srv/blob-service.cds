using { customer.cap_java as db } from '../db/documents';

@path: 'blob'
@requires: 'KsefOperator'
service BlobService {

    @restrict: [
        {
            grant: 'CREATE',
            to: 'KsefOperator'
        },
        {
            grant: ['READ', 'UPDATE'],
            to: 'KsefOperator',
            where: (createdBy = $user)
        }
    ]
    entity Documents as projection on db.KsefDocuments
    {
        key ID,
        fileName,
        mimeType,
        content,
        sha256,
        createdBy,
        createdAt
    };
}

annotate BlobService.Documents with {
    createdBy @readonly;
    createdAt @readonly;
    sha256    @readonly;
};
