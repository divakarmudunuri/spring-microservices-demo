import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './models';

/** The ProblemDetail body of a failed call, if it has one. */
export function problemOf(error: unknown): ProblemDetail | null {
  if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object') {
    return error.error as ProblemDetail;
  }
  return null;
}

/** A short, human message for any failed call: the ProblemDetail's title/detail when there is one. */
export function problemMessage(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Something went wrong.';
  }
  if (error.status === 0) {
    return "Can't reach the store right now. Check your connection and try again.";
  }
  const problem = problemOf(error);
  if (problem?.title || problem?.detail) {
    const { title, detail } = problem;
    if (title && detail && !detail.includes(title)) {
      return `${title}: ${detail}`;
    }
    return detail || title!;
  }
  if (error.status === 429) {
    return 'Too many requests. Please wait a moment and try again.';
  }
  if (error.status >= 500) {
    return 'The store is having trouble right now. Please try again shortly.';
  }
  return `Request failed (${error.status}).`;
}
