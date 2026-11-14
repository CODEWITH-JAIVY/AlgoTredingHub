/** Identifies the newest asynchronous load and invalidates work after cleanup. */
export class RequestEpoch {
  private value = 0;

  begin(): number {
    this.value += 1;
    return this.value;
  }

  isCurrent(epoch: number): boolean {
    return this.value === epoch;
  }

  invalidate(): void {
    this.value += 1;
  }
}
