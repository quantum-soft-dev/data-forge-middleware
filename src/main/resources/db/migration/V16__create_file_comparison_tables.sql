-- Migration V16: Create file comparison tables
-- Feature: File Diff Comparison Between Upload Sessions (Spec 009)
-- Date: 2025-11-03

-- Table: file_comparisons
CREATE TABLE file_comparisons (
    id BIGSERIAL PRIMARY KEY,
    current_batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    target_batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    total_files_compared INTEGER NOT NULL DEFAULT 0,
    files_changed INTEGER NOT NULL DEFAULT 0,
    files_added INTEGER NOT NULL DEFAULT 0,
    files_unchanged INTEGER NOT NULL DEFAULT 0,
    total_change_size BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    CONSTRAINT chk_file_comparisons_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_file_comparisons_batch_ids CHECK (current_batch_id != target_batch_id),
    CONSTRAINT chk_file_comparisons_statistics CHECK (
        total_files_compared >= 0 AND
        files_changed >= 0 AND
        files_added >= 0 AND
        files_unchanged >= 0 AND
        (total_files_compared = files_changed + files_added + files_unchanged)
    )
);

-- Indexes for file_comparisons
CREATE INDEX idx_file_comparisons_account_id ON file_comparisons(account_id);
CREATE INDEX idx_file_comparisons_current_batch ON file_comparisons(current_batch_id);
CREATE INDEX idx_file_comparisons_target_batch ON file_comparisons(target_batch_id);
CREATE INDEX idx_file_comparisons_status ON file_comparisons(status);
CREATE INDEX idx_file_comparisons_created_at ON file_comparisons(created_at DESC);
CREATE INDEX idx_file_comparisons_account_created ON file_comparisons(account_id, created_at DESC);

-- Table: comparison_results
CREATE TABLE comparison_results (
    id BIGSERIAL PRIMARY KEY,
    comparison_id BIGINT NOT NULL REFERENCES file_comparisons(id) ON DELETE CASCADE,
    file_id BIGINT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    target_file_id BIGINT REFERENCES files(id) ON DELETE CASCADE,
    change_type VARCHAR(20) NOT NULL,
    unified_diff JSONB,
    line_additions INTEGER NOT NULL DEFAULT 0,
    line_deletions INTEGER NOT NULL DEFAULT 0,
    change_size BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_comparison_results_change_type CHECK (change_type IN ('ADDED', 'MODIFIED', 'UNCHANGED', 'REMOVED')),
    CONSTRAINT chk_comparison_results_target_file CHECK (
        (change_type = 'ADDED' AND target_file_id IS NULL) OR
        (change_type != 'ADDED' AND target_file_id IS NOT NULL)
    ),
    CONSTRAINT chk_comparison_results_line_counts CHECK (line_additions >= 0 AND line_deletions >= 0)
);

-- Indexes for comparison_results
CREATE INDEX idx_comparison_results_comparison_id ON comparison_results(comparison_id);
CREATE INDEX idx_comparison_results_file_id ON comparison_results(file_id);
CREATE INDEX idx_comparison_results_change_type ON comparison_results(change_type);
CREATE INDEX idx_comparison_results_comparison_change ON comparison_results(comparison_id, change_type);
CREATE INDEX idx_comparison_results_diff ON comparison_results USING GIN(unified_diff);

-- Comments for documentation
COMMENT ON TABLE file_comparisons IS 'Stores metadata for file comparison operations between upload sessions (batches)';
COMMENT ON TABLE comparison_results IS 'Stores individual file diff results for each comparison operation';
COMMENT ON COLUMN file_comparisons.status IS 'Lifecycle state: PENDING, IN_PROGRESS, COMPLETED, FAILED';
COMMENT ON COLUMN comparison_results.change_type IS 'Type of change: ADDED, MODIFIED, UNCHANGED, REMOVED';
COMMENT ON COLUMN comparison_results.unified_diff IS 'Diff output stored as structured JSONB for queryability';
