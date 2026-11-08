# knowledge-index Specification

## Purpose
TBD - created by archiving change r6-3-knowledge-memory. Update Purpose after archive.
## Requirements
### Requirement: Knowledge sources are immutable and authorized
The system SHALL bind each indexed document to one authorized immutable source version and verify its ownership and content hash before extraction, indexing, search and content delivery.

#### Scenario: Authorized file version is added
- **WHEN** an owner adds a visible fileVersionId or attachmentId to a knowledge base
- **THEN** the system records that exact source identity and its verified hash without copying a second original

#### Scenario: Source is revoked or replaced
- **WHEN** membership is revoked, the source is deleted, or the requested source version is no longer readable
- **THEN** new search results and model admission omit or reject that source before returning its content

#### Scenario: Invalid or unbounded input
- **WHEN** an upload exceeds 100 MiB, extracted text exceeds 2 MiB, a PDF exceeds 200 pages, or extraction is damaged/encrypted/empty
- **THEN** the operation fails with a stable reason and never publishes truncated text as a complete document

### Requirement: Index builds are resumable and atomically activated
The system SHALL persist build identity and committed chunk ordinal, make retries idempotent, and activate a complete index version atomically while preserving the previous active version on failure.

#### Scenario: Duplicate delivery resumes the same build
- **WHEN** a broker message is redelivered or a worker restarts after some chunks were committed
- **THEN** the same document/indexVersion continues from its durable ordinal without duplicate chunks or object charges

#### Scenario: Partial build fails
- **WHEN** parsing, embedding, storage, or ownership validation fails before the new index is complete
- **THEN** the previous active index remains searchable and the incomplete version stays non-active for reconciliation

#### Scenario: Completed build becomes active
- **WHEN** all chunks, hashes, vector dimensions, locations and source version checks pass under the current fence
- **THEN** one transaction changes the active index pointer and persists the state event

### Requirement: Retrieval ranks only authorized candidates
The system SHALL filter by current user/space/source ACL and active index version before vector and lexical ranking.

#### Scenario: Hybrid search returns a match
- **WHEN** the authorized candidate set contains semantic and lexical matches
- **THEN** each channel returns at most 20 candidates and RRF k=60 produces up to 8 deduplicated source-bound segments of at most 1000 characters

#### Scenario: Cross-space document is a stronger match
- **WHEN** an unauthorized space has a higher similarity score than the authorized space
- **THEN** the unauthorized document is not considered for ranking and no metadata or text from it is returned

#### Scenario: Semantic encoder is unavailable
- **WHEN** an optional semantic search can degrade safely
- **THEN** the response identifies lexical-only degradation; when semantic is required, the request fails explicitly instead of claiming a lexical result is semantic

#### Scenario: No-answer query
- **WHEN** the fixed corpus contains no authorized supporting segment
- **THEN** the search result has no fabricated source references

### Requirement: Search evidence preserves source location
The system SHALL return an immutable source/version/hash and an extractor-supported location for each hit.

#### Scenario: Format has a reliable location
- **WHEN** the extraction manifest contains a PDF page, slide, sheet/range, paragraph or text offset
- **THEN** the result returns that exact location and source revision

#### Scenario: Location cannot be established
- **WHEN** the extractor cannot provide a reliable page or range
- **THEN** the result marks location as unknown instead of manufacturing a position
