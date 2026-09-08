/**
 * RFC 7807 Problem Detail — matches backend ProblemDetail (7 fields).
 * @see libs/karyo-common/src/main/kotlin/com/karyo/common/exception/ProblemDetail.kt
 */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  traceId?: string;
  timestamp?: string;
  /** Validation constraint violations (Bean Validation errors) */
  violations?: Array<{
    field: string;
    message: string;
    rejectedValue?: unknown;
  }>;
}

/**
 * Standard paginated response shape from backend services.
 * @see docs/architecture/api-standards.md
 */
export interface PaginatedResponse<T> {
  content: T[];
  page: {
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
  };
}
