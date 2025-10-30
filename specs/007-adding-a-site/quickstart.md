# Quickstart Guide: Site Management Implementation

**Feature**: Site Management for Users and Admins
**Branch**: `007-adding-a-site`
**Date**: 2025-10-30

## Overview

This guide provides step-by-step instructions for implementing the site management feature following TDD principles and the project constitution. The feature extends the existing Site domain with frontend UI and admin capabilities.

---

## Prerequisites

Before starting, ensure you have:

- ✅ Java 21 JDK installed
- ✅ Node.js 18+ and npm installed
- ✅ PostgreSQL 16 running locally or via Docker
- ✅ Keycloak running with `dataforge` realm configured
- ✅ IDE with Java (IntelliJ IDEA recommended) and TypeScript support (VS Code recommended)
- ✅ Git repository cloned and on branch `007-adding-a-site`

**Verify prerequisites:**
```bash
# Check Java version
java -version  # Should show Java 21

# Check Node version
node --version  # Should show v18.x or higher

# Check PostgreSQL
psql --version  # Should show PostgreSQL 16

# Verify branch
git branch --show-current  # Should show 007-adding-a-site
```

---

## Phase 1: Backend Implementation (TDD)

### Step 1.1: Database Migration

**Create Flyway migration for admin action logs:**

```bash
# File: src/main/resources/db/migration/V008__add_admin_action_logs.sql
```

Copy the SQL from `data-model.md` section "Flyway Migration V008".

**Run migration:**
```bash
./gradlew flywayMigrate
```

**Verify migration:**
```bash
psql -U postgres -d dataforge -c "\d admin_action_logs"
```

Expected output: Table with columns id, action_type, target_account_id, target_site_id, admin_account_id, ip_address, user_agent, success, error_message, created_at.

---

### Step 1.2: Domain Layer - Password Generator

**Create domain service for password generation:**

```java
// File: src/main/java/com/bitbi/dfm/site/domain/PasswordGenerator.java

package com.bitbi.dfm.site.domain;

import org.springframework.stereotype.Service;
import java.security.SecureRandom;

/**
 * Domain service for generating secure random passwords.
 * Generates 8-12 character passwords with mixed letters (A-Z, a-z) and numbers (0-9).
 */
@Service
public class PasswordGenerator {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 12;
    private final SecureRandom random = new SecureRandom();

    /**
     * Generates a secure random password.
     * @return Password string (8-12 characters, mixed letters and numbers)
     */
    public String generate() {
        int length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
        StringBuilder password = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            password.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }

        return password.toString();
    }
}
```

**Write unit test:**

```java
// File: src/test/java/com/bitbi/dfm/site/domain/PasswordGeneratorTest.java

package com.bitbi.dfm.site.domain;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordGeneratorTest {

    private final PasswordGenerator generator = new PasswordGenerator();

    @Test
    void shouldGeneratePasswordWithCorrectLength() {
        String password = generator.generate();
        assertThat(password).hasSizeBetween(8, 12);
    }

    @Test
    void shouldGeneratePasswordWithLettersAndNumbers() {
        String password = generator.generate();
        assertThat(password).matches("^[A-Za-z0-9]+$");
    }

    @Test
    void shouldGenerateUniquePasswords() {
        Set<String> passwords = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            passwords.add(generator.generate());
        }
        // With high probability, 100 random passwords should be unique
        assertThat(passwords).hasSizeGreaterThan(95);
    }
}
```

**Run test:**
```bash
./gradlew test --tests PasswordGeneratorTest
```

---

### Step 1.3: Domain Layer - AdminActionLog Entity

**Create AdminActionLog entity:**

```java
// File: src/main/java/com/bitbi/dfm/adminactionlog/domain/AdminActionLog.java

package com.bitbi.dfm.adminactionlog.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_action_logs")
@Getter
@NoArgsConstructor
public class AdminActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private AdminActionType actionType;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "target_site_id")
    private UUID targetSiteId;

    @Column(name = "admin_account_id", nullable = false)
    private UUID adminAccountId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(nullable = false)
    private Boolean success;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AdminActionLog(
            AdminActionType actionType,
            UUID targetAccountId,
            UUID targetSiteId,
            UUID adminAccountId,
            String ipAddress,
            String userAgent,
            Boolean success,
            String errorMessage
    ) {
        this.actionType = actionType;
        this.targetAccountId = targetAccountId;
        this.targetSiteId = targetSiteId;
        this.adminAccountId = adminAccountId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.success = success;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    public enum AdminActionType {
        CREATE_SITE,
        DEACTIVATE_SITE,
        ACTIVATE_SITE,
        DELETE_SITE
    }
}
```

**Create repository interface:**

```java
// File: src/main/java/com/bitbi/dfm/adminactionlog/domain/AdminActionLogRepository.java

package com.bitbi.dfm.adminactionlog.domain;

import java.util.UUID;

public interface AdminActionLogRepository {
    AdminActionLog save(AdminActionLog log);
}
```

**Create JPA implementation:**

```java
// File: src/main/java/com/bitbi/dfm/adminactionlog/infrastructure/JpaAdminActionLogRepository.java

package com.bitbi.dfm.adminactionlog.infrastructure;

import com.bitbi.dfm.adminactionlog.domain.AdminActionLog;
import com.bitbi.dfm.adminactionlog.domain.AdminActionLogRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
interface SpringDataAdminActionLogRepository extends JpaRepository<AdminActionLog, UUID> {
}

@Repository
public class JpaAdminActionLogRepository implements AdminActionLogRepository {

    private final SpringDataAdminActionLogRepository repository;

    public JpaAdminActionLogRepository(SpringDataAdminActionLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public AdminActionLog save(AdminActionLog log) {
        return repository.save(log);
    }
}
```

---

### Step 1.4: Application Layer - Extend SiteService

**Add methods to SiteService:**

```java
// File: src/main/java/com/bitbi/dfm/site/application/SiteService.java

// Add these methods to existing SiteService class:

/**
 * List all active sites for an account (sorted by created_at DESC).
 * Used by both user and admin endpoints.
 */
public List<Site> listAccountSites(UUID accountId) {
    return siteRepository.findByAccountIdAndIsActiveTrueOrderByCreatedAtDesc(accountId);
}

/**
 * Deactivate a site (user or admin operation).
 */
public Site deactivateSite(UUID siteId, UUID accountId) {
    Site site = siteRepository.findByIdAndAccountId(siteId, accountId)
            .orElseThrow(() -> new SiteNotFoundException("Site not found"));

    if (!site.isActive()) {
        throw new IllegalStateException("Site is already inactive");
    }

    site.deactivate();  // Assuming Site entity has this method
    return siteRepository.save(site);
}

/**
 * Activate a site (user or admin operation).
 */
public Site activateSite(UUID siteId, UUID accountId) {
    Site site = siteRepository.findByIdAndAccountId(siteId, accountId)
            .orElseThrow(() -> new SiteNotFoundException("Site not found"));

    if (site.isActive()) {
        throw new IllegalStateException("Site is already active");
    }

    site.activate();  // Assuming Site entity has this method
    return siteRepository.save(site);
}

/**
 * Soft-delete a site (user or admin operation).
 */
public void deleteSite(UUID siteId, UUID accountId) {
    Site site = siteRepository.findByIdAndAccountId(siteId, accountId)
            .orElseThrow(() -> new SiteNotFoundException("Site not found"));

    site.deactivate();  // Soft delete via isActive = false
    siteRepository.save(site);
}
```

**Update SiteRepository interface:**

```java
// File: src/main/java/com/bitbi/dfm/site/domain/SiteRepository.java

// Add these methods to existing interface:
List<Site> findByAccountIdAndIsActiveTrueOrderByCreatedAtDesc(UUID accountId);
Optional<Site> findByIdAndAccountId(UUID siteId, UUID accountId);
```

---

### Step 1.5: Presentation Layer - DTOs

**Create CreateSiteRequestDto:**

```java
// File: src/main/java/com/bitbi/dfm/site/presentation/dto/CreateSiteRequestDto.java

package com.bitbi.dfm.site.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSiteRequestDto(
    @NotBlank(message = "Domain is required")
    @Size(min = 3, max = 255, message = "Domain must be 3-255 characters")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain can only contain lowercase letters, numbers, dots, and hyphens")
    String domain,

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password
) {}
```

**Update SiteResponseDto (extend existing):**

```java
// File: src/main/java/com/bitbi/dfm/site/presentation/dto/SiteResponseDto.java

// Extend existing SiteResponseDto with these fields if not present:
public record SiteResponseDto(
    UUID id,
    UUID accountId,
    String domain,
    Boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
    public static SiteResponseDto fromEntity(Site site) {
        return new SiteResponseDto(
            site.getId(),
            site.getAccountId(),
            site.getDomain(),
            site.isActive(),
            site.getCreatedAt(),
            site.getUpdatedAt()
        );
    }
}
```

---

### Step 1.6: Presentation Layer - User Endpoints

**Extend SiteController with new endpoints:**

```java
// File: src/main/java/com/bitbi/dfm/site/presentation/SiteController.java

@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteService siteService;

    // ... existing endpoints ...

    @GetMapping
    public ResponseEntity<List<SiteResponseDto>> listUserSites(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = extractAccountIdFromJwt(jwt);
        List<Site> sites = siteService.listAccountSites(accountId);
        return ResponseEntity.ok(sites.stream()
                .map(SiteResponseDto::fromEntity)
                .toList());
    }

    @PostMapping
    public ResponseEntity<SiteResponseDto> createSite(
            @Valid @RequestBody CreateSiteRequestDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID accountId = extractAccountIdFromJwt(jwt);
        Site site = siteService.createSite(accountId, request.domain(), request.password());
        return ResponseEntity
                .created(URI.create("/api/sites/" + site.getId()))
                .body(SiteResponseDto.fromEntity(site));
    }

    @PostMapping("/{siteId}/deactivate")
    public ResponseEntity<SiteResponseDto> deactivateSite(
            @PathVariable UUID siteId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID accountId = extractAccountIdFromJwt(jwt);
        Site site = siteService.deactivateSite(siteId, accountId);
        return ResponseEntity.ok(SiteResponseDto.fromEntity(site));
    }

    @PostMapping("/{siteId}/activate")
    public ResponseEntity<SiteResponseDto> activateSite(
            @PathVariable UUID siteId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID accountId = extractAccountIdFromJwt(jwt);
        Site site = siteService.activateSite(siteId, accountId);
        return ResponseEntity.ok(SiteResponseDto.fromEntity(site));
    }

    @DeleteMapping("/{siteId}")
    public ResponseEntity<Void> deleteSite(
            @PathVariable UUID siteId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID accountId = extractAccountIdFromJwt(jwt);
        siteService.deleteSite(siteId, accountId);
        return ResponseEntity.noContent().build();
    }

    private UUID extractAccountIdFromJwt(Jwt jwt) {
        // Extract accountId from JWT claims (implementation depends on token structure)
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
```

---

### Step 1.7: Presentation Layer - Admin Endpoints

**Extend SiteAdminController with site management endpoints:**

```java
// File: src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java

@RestController
@RequestMapping("/api/admin/accounts/{accountId}/sites")
@PreAuthorize("hasRole('ADMIN')")
public class SiteAdminController {

    private final SiteService siteService;
    private final AdminActionLogRepository adminActionLogRepository;

    // Inject dependencies via constructor

    @GetMapping
    public ResponseEntity<List<SiteResponseDto>> listAccountSites(@PathVariable UUID accountId) {
        List<Site> sites = siteService.listAccountSites(accountId);
        return ResponseEntity.ok(sites.stream()
                .map(SiteResponseDto::fromEntity)
                .toList());
    }

    @PostMapping
    public ResponseEntity<SiteResponseDto> createSiteForAccount(
            @PathVariable UUID accountId,
            @Valid @RequestBody CreateSiteRequestDto request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest
    ) {
        UUID adminAccountId = extractAccountIdFromJwt(jwt);
        Site site = siteService.createSite(accountId, request.domain(), request.password());

        // Log admin action
        logAdminAction(
                AdminActionType.CREATE_SITE,
                accountId,
                site.getId(),
                adminAccountId,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"),
                true,
                null
        );

        return ResponseEntity
                .created(URI.create("/api/admin/accounts/" + accountId + "/sites/" + site.getId()))
                .body(SiteResponseDto.fromEntity(site));
    }

    @PostMapping("/{siteId}/deactivate")
    public ResponseEntity<SiteResponseDto> deactivateSiteForAccount(
            @PathVariable UUID accountId,
            @PathVariable UUID siteId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest
    ) {
        UUID adminAccountId = extractAccountIdFromJwt(jwt);
        Site site = siteService.deactivateSite(siteId, accountId);

        logAdminAction(
                AdminActionType.DEACTIVATE_SITE,
                accountId,
                siteId,
                adminAccountId,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"),
                true,
                null
        );

        return ResponseEntity.ok(SiteResponseDto.fromEntity(site));
    }

    @PostMapping("/{siteId}/activate")
    public ResponseEntity<SiteResponseDto> activateSiteForAccount(
            @PathVariable UUID accountId,
            @PathVariable UUID siteId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest
    ) {
        UUID adminAccountId = extractAccountIdFromJwt(jwt);
        Site site = siteService.activateSite(siteId, accountId);

        logAdminAction(
                AdminActionType.ACTIVATE_SITE,
                accountId,
                siteId,
                adminAccountId,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"),
                true,
                null
        );

        return ResponseEntity.ok(SiteResponseDto.fromEntity(site));
    }

    @DeleteMapping("/{siteId}")
    public ResponseEntity<Void> deleteSiteForAccount(
            @PathVariable UUID accountId,
            @PathVariable UUID siteId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest
    ) {
        UUID adminAccountId = extractAccountIdFromJwt(jwt);
        siteService.deleteSite(siteId, accountId);

        logAdminAction(
                AdminActionType.DELETE_SITE,
                accountId,
                siteId,
                adminAccountId,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"),
                true,
                null
        );

        return ResponseEntity.noContent().build();
    }

    private void logAdminAction(
            AdminActionType actionType,
            UUID targetAccountId,
            UUID targetSiteId,
            UUID adminAccountId,
            String ipAddress,
            String userAgent,
            boolean success,
            String errorMessage
    ) {
        AdminActionLog log = new AdminActionLog(
                actionType,
                targetAccountId,
                targetSiteId,
                adminAccountId,
                ipAddress,
                userAgent,
                success,
                errorMessage
        );
        adminActionLogRepository.save(log);
    }

    private UUID extractAccountIdFromJwt(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
```

---

### Step 1.8: Backend Testing

**Run all backend tests:**

```bash
./gradlew test
./gradlew integrationTest
```

**Verify coverage:**

```bash
./gradlew jacocoTestReport
# Open build/reports/jacoco/test/html/index.html
# Check coverage ≥80%
```

---

## Phase 2: Frontend Implementation (TDD)

### Step 2.1: Setup Development Environment

**Install dependencies (if not already done):**

```bash
cd frontend
npm install
```

**Start development server:**

```bash
npm run dev
```

---

### Step 2.2: Shared Layer - Password Generator Utility

**Create password generator utility:**

```typescript
// File: frontend/src/shared/lib/password-generator.ts

const CHARACTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const MIN_LENGTH = 8;
const MAX_LENGTH = 12;

/**
 * Generates a secure random password using Web Crypto API.
 * Matches backend PasswordGenerator algorithm.
 * @returns Random password (8-12 characters, letters and numbers)
 */
export function generatePassword(): string {
  const length = MIN_LENGTH + Math.floor(Math.random() * (MAX_LENGTH - MIN_LENGTH + 1));
  const randomValues = new Uint8Array(length);
  crypto.getRandomValues(randomValues);

  let password = '';
  for (let i = 0; i < length; i++) {
    password += CHARACTERS.charAt(randomValues[i] % CHARACTERS.length);
  }

  return password;
}
```

**Write unit test (TDD - write first):**

```typescript
// File: frontend/src/shared/lib/password-generator.test.ts

import { describe, it, expect } from 'vitest';
import { generatePassword } from './password-generator';

describe('generatePassword', () => {
  it('should generate password with correct length', () => {
    const password = generatePassword();
    expect(password.length).toBeGreaterThanOrEqual(8);
    expect(password.length).toBeLessThanOrEqual(12);
  });

  it('should generate password with only letters and numbers', () => {
    const password = generatePassword();
    expect(password).toMatch(/^[A-Za-z0-9]+$/);
  });

  it('should generate unique passwords', () => {
    const passwords = new Set<string>();
    for (let i = 0; i < 100; i++) {
      passwords.add(generatePassword());
    }
    // With high probability, 100 random passwords should be unique
    expect(passwords.size).toBeGreaterThan(95);
  });
});
```

**Run test:**

```bash
npm test password-generator
```

---

### Step 2.3: Entities Layer - Site API Client

**Create site types:**

```typescript
// File: frontend/src/entities/site/model/types.ts

export interface Site {
  id: string;
  accountId: string;
  domain: string;
  isActive: boolean;
  createdAt: string;  // ISO 8601
  updatedAt: string;  // ISO 8601
}

export interface CreateSiteRequest {
  domain: string;
  password: string;
}
```

**Create API client:**

```typescript
// File: frontend/src/entities/site/api/siteApi.ts

import { apiClient } from '@/shared/api/client';
import type { Site, CreateSiteRequest } from '../model/types';

export const siteApi = {
  // User endpoints
  listUserSites: async (): Promise<Site[]> => {
    const response = await apiClient.get<Site[]>('/api/sites');
    return response.data;
  },

  createSite: async (request: CreateSiteRequest): Promise<Site> => {
    const response = await apiClient.post<Site>('/api/sites', request);
    return response.data;
  },

  deactivateSite: async (siteId: string): Promise<Site> => {
    const response = await apiClient.post<Site>(`/api/sites/${siteId}/deactivate`);
    return response.data;
  },

  activateSite: async (siteId: string): Promise<Site> => {
    const response = await apiClient.post<Site>(`/api/sites/${siteId}/activate`);
    return response.data;
  },

  deleteSite: async (siteId: string): Promise<void> => {
    await apiClient.delete(`/api/sites/${siteId}`);
  },

  // Admin endpoints
  listAccountSites: async (accountId: string): Promise<Site[]> => {
    const response = await apiClient.get<Site[]>(`/api/admin/accounts/${accountId}/sites`);
    return response.data;
  },

  createSiteForAccount: async (accountId: string, request: CreateSiteRequest): Promise<Site> => {
    const response = await apiClient.post<Site>(`/api/admin/accounts/${accountId}/sites`, request);
    return response.data;
  },

  deactivateSiteForAccount: async (accountId: string, siteId: string): Promise<Site> => {
    const response = await apiClient.post<Site>(`/api/admin/accounts/${accountId}/sites/${siteId}/deactivate`);
    return response.data;
  },

  activateSiteForAccount: async (accountId: string, siteId: string): Promise<Site> => {
    const response = await apiClient.post<Site>(`/api/admin/accounts/${accountId}/sites/${siteId}/activate`);
    return response.data;
  },

  deleteSiteForAccount: async (accountId: string, siteId: string): Promise<void> => {
    await apiClient.delete(`/api/admin/accounts/${accountId}/sites/${siteId}`);
  },
};
```

**Create public API:**

```typescript
// File: frontend/src/entities/site/index.ts

export { siteApi } from './api/siteApi';
export type { Site, CreateSiteRequest } from './model/types';
```

---

### Step 2.4: Features Layer - Zod Schemas

**Create validation schemas:**

```typescript
// File: frontend/src/features/site-crud/model/schemas.ts

import { z } from 'zod';

export const CreateSiteFormSchema = z.object({
  domain: z.string()
    .min(3, "Domain must be at least 3 characters")
    .max(255, "Domain too long")
    .regex(/^[a-z0-9.-]+$/, "Domain can only contain lowercase letters, numbers, dots, and hyphens"),
  password: z.string()
    .min(8, "Password must be at least 8 characters"),
});

export type CreateSiteFormData = z.infer<typeof CreateSiteFormSchema>;
```

---

### Step 2.5: Features Layer - TanStack Query Hooks

**Create query hooks:**

```typescript
// File: frontend/src/features/site-crud/model/queries.ts

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { siteApi, type CreateSiteRequest } from '@/entities/site';
import { toast } from 'sonner';

// Query keys
export const siteKeys = {
  all: ['sites'] as const,
  lists: () => [...siteKeys.all, 'list'] as const,
  list: (accountId: string) => [...siteKeys.lists(), accountId] as const,
  adminLists: () => [...siteKeys.all, 'admin'] as const,
  adminList: (accountId: string) => [...siteKeys.adminLists(), accountId] as const,
};

// User queries
export function useSites() {
  return useQuery({
    queryKey: siteKeys.lists(),
    queryFn: siteApi.listUserSites,
  });
}

export function useCreateSite() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: CreateSiteRequest) => siteApi.createSite(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: siteKeys.lists() });
      toast.success('Site created successfully');
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || 'Failed to create site');
    },
  });
}

export function useUpdateSiteStatus() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ siteId, action }: { siteId: string; action: 'activate' | 'deactivate' }) => {
      return action === 'activate' ? siteApi.activateSite(siteId) : siteApi.deactivateSite(siteId);
    },
    onMutate: async ({ siteId, action }) => {
      // Optimistic update
      await queryClient.cancelQueries({ queryKey: siteKeys.lists() });
      const previousSites = queryClient.getQueryData(siteKeys.lists());

      queryClient.setQueryData(siteKeys.lists(), (old: any) =>
        old.map((site: any) =>
          site.id === siteId ? { ...site, isActive: action === 'activate' } : site
        )
      );

      return { previousSites };
    },
    onError: (error, variables, context) => {
      queryClient.setQueryData(siteKeys.lists(), context?.previousSites);
      toast.error('Failed to update site status');
    },
    onSuccess: () => {
      toast.success('Site status updated');
    },
  });
}

export function useDeleteSite() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (siteId: string) => siteApi.deleteSite(siteId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: siteKeys.lists() });
      toast.success('Site deleted successfully');
    },
    onError: () => {
      toast.error('Failed to delete site');
    },
  });
}

// Admin queries (similar pattern)
export function useAdminSites(accountId: string) {
  return useQuery({
    queryKey: siteKeys.adminList(accountId),
    queryFn: () => siteApi.listAccountSites(accountId),
    enabled: !!accountId,
  });
}

// ... similar mutations for admin operations ...
```

---

### Step 2.6: Features Layer - Create Site Form

**Create form component:**

```typescript
// File: frontend/src/features/site-crud/ui/CreateSiteForm.tsx

import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Button } from '@/shared/ui/components/button';
import { Input } from '@/shared/ui/components/input';
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/shared/ui/components/form';
import { generatePassword } from '@/shared/lib/password-generator';
import { CreateSiteFormSchema, type CreateSiteFormData } from '../model/schemas';
import { useCreateSite } from '../model/queries';

interface CreateSiteFormProps {
  onSuccess?: () => void;
}

export function CreateSiteForm({ onSuccess }: CreateSiteFormProps) {
  const form = useForm<CreateSiteFormData>({
    resolver: zodResolver(CreateSiteFormSchema),
    defaultValues: {
      domain: '',
      password: '',
    },
  });

  const createSite = useCreateSite();

  const handleGeneratePassword = () => {
    const password = generatePassword();
    form.setValue('password', password);
  };

  const onSubmit = (data: CreateSiteFormData) => {
    createSite.mutate(data, {
      onSuccess: () => {
        form.reset();
        onSuccess?.();
      },
    });
  };

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
        <FormField
          control={form.control}
          name="domain"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Domain</FormLabel>
              <FormControl>
                <Input placeholder="example.com" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="password"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Password</FormLabel>
              <div className="flex gap-2">
                <FormControl>
                  <Input type="password" placeholder="Min 8 characters" {...field} />
                </FormControl>
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleGeneratePassword}
                >
                  Generate
                </Button>
              </div>
              <FormMessage />
            </FormItem>
          )}
        />

        <Button type="submit" disabled={createSite.isPending}>
          {createSite.isPending ? 'Creating...' : 'Create Site'}
        </Button>
      </form>
    </Form>
  );
}
```

---

### Step 2.7: Widgets Layer - Site List

**Create site list widget:**

```typescript
// File: frontend/src/widgets/site-list/SiteList.tsx

import { Button } from '@/shared/ui/components/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/ui/components/table';
import { Badge } from '@/shared/ui/components/badge';
import type { Site } from '@/entities/site';
import { useUpdateSiteStatus, useDeleteSite } from '@/features/site-crud/model/queries';

interface SiteListProps {
  sites: Site[];
  onDelete?: (siteId: string) => void;
}

export function SiteList({ sites, onDelete }: SiteListProps) {
  const updateStatus = useUpdateSiteStatus();
  const deleteSite = useDeleteSite();

  if (sites.length === 0) {
    return (
      <div className="text-center py-8 text-muted-foreground">
        No sites found. Create your first site to get started.
      </div>
    );
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Domain</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Created</TableHead>
          <TableHead>Actions</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {sites.map((site) => (
          <TableRow key={site.id}>
            <TableCell className="font-medium">{site.domain}</TableCell>
            <TableCell>
              <Badge variant={site.isActive ? 'default' : 'secondary'}>
                {site.isActive ? 'Active' : 'Inactive'}
              </Badge>
            </TableCell>
            <TableCell>{new Date(site.createdAt).toLocaleDateString()}</TableCell>
            <TableCell>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => updateStatus.mutate({
                    siteId: site.id,
                    action: site.isActive ? 'deactivate' : 'activate'
                  })}
                  disabled={updateStatus.isPending}
                >
                  {site.isActive ? 'Deactivate' : 'Activate'}
                </Button>
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={() => onDelete?.(site.id)}
                >
                  Delete
                </Button>
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
```

---

### Step 2.8: Pages Layer - Site Management Page

**Create user site management page:**

```typescript
// File: frontend/src/pages/site-management/SiteManagementPage.tsx

import { useState } from 'react';
import { Button } from '@/shared/ui/components/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/shared/ui/components/dialog';
import { useSites, useDeleteSite } from '@/features/site-crud/model/queries';
import { CreateSiteForm } from '@/features/site-crud/ui/CreateSiteForm';
import { SiteList } from '@/widgets/site-list/SiteList';
import { DeleteSiteDialog } from '@/features/site-crud/ui/DeleteSiteDialog';

export function SiteManagementPage() {
  const { data: sites, isLoading, error } = useSites();
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false);
  const [deletingSiteId, setDeletingSiteId] = useState<string | null>(null);

  if (isLoading) return <div>Loading sites...</div>;
  if (error) return <div>Error loading sites: {error.message}</div>;

  return (
    <div className="container py-8">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-3xl font-bold">Site Management</h1>
        <Dialog open={isCreateDialogOpen} onOpenChange={setIsCreateDialogOpen}>
          <DialogTrigger asChild>
            <Button>Add Site</Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Create New Site</DialogTitle>
            </DialogHeader>
            <CreateSiteForm onSuccess={() => setIsCreateDialogOpen(false)} />
          </DialogContent>
        </Dialog>
      </div>

      <SiteList
        sites={sites || []}
        onDelete={(siteId) => setDeletingSiteId(siteId)}
      />

      {deletingSiteId && (
        <DeleteSiteDialog
          siteId={deletingSiteId}
          onClose={() => setDeletingSiteId(null)}
        />
      )}
    </div>
  );
}
```

---

### Step 2.9: Frontend Testing

**Run frontend tests:**

```bash
npm test
```

**Run E2E tests:**

```bash
npm run test:e2e
```

**Check coverage:**

```bash
npm run test:coverage
# Check coverage ≥80%
```

---

## Phase 3: Integration Testing

### Step 3.1: Start All Services

```bash
# Terminal 1: Backend
./gradlew bootRun

# Terminal 2: Frontend
cd frontend && npm run dev

# Terminal 3: Keycloak (if not already running)
docker-compose up keycloak
```

### Step 3.2: Manual Testing Checklist

- [ ] **User Flow**:
  - [ ] Login as regular user
  - [ ] Navigate to Site Management
  - [ ] Create new site with manual password
  - [ ] Create new site with generated password
  - [ ] Verify sites appear in list (newest first)
  - [ ] Deactivate a site
  - [ ] Activate a site
  - [ ] Delete a site (confirm dialog)
  - [ ] Verify deleted site no longer appears

- [ ] **Admin Flow**:
  - [ ] Login as admin user
  - [ ] Navigate to User Management
  - [ ] Select a user account
  - [ ] Navigate to user's site management
  - [ ] Create site for user
  - [ ] Deactivate/activate/delete site for user
  - [ ] Verify audit logs in database

### Step 3.3: Verify Audit Logging

```sql
-- Query admin action logs
SELECT * FROM admin_action_logs
ORDER BY created_at DESC
LIMIT 10;

-- Verify all admin actions logged
SELECT action_type, COUNT(*) as count
FROM admin_action_logs
GROUP BY action_type;
```

---

## Phase 4: Deployment Checklist

- [ ] **Code Quality**:
  - [ ] Backend tests passing (≥80% coverage)
  - [ ] Frontend tests passing (≥80% coverage)
  - [ ] No TypeScript errors
  - [ ] No ESLint errors
  - [ ] Bundle size <500KB

- [ ] **Security**:
  - [ ] No credentials in source code
  - [ ] Keycloak integration working
  - [ ] ROLE_ADMIN check enforced
  - [ ] Input validation working
  - [ ] XSS prevention verified

- [ ] **Performance**:
  - [ ] Site list loads <2s for 50 sites
  - [ ] Site deactivation <1s
  - [ ] API p95 latency <1000ms

- [ ] **Documentation**:
  - [ ] OpenAPI specs generated
  - [ ] README updated
  - [ ] CLAUDE.md updated with new endpoints

---

## Troubleshooting

### Backend Issues

**Issue**: Flyway migration fails
```bash
# Solution: Check migration file syntax
./gradlew flywayInfo
./gradlew flywayValidate
```

**Issue**: Tests failing with "Site not found"
```bash
# Solution: Verify test data setup in @BeforeEach
# Ensure accountId and siteId exist in test database
```

### Frontend Issues

**Issue**: TanStack Query not fetching data
```bash
# Solution: Check API client baseURL configuration
# Verify Keycloak token is present in axios interceptor
```

**Issue**: Form validation not working
```bash
# Solution: Verify Zod schema matches backend DTO validation
# Check zodResolver is properly configured in useForm
```

---

## Next Steps

After completing this quickstart:

1. Run `/speckit.tasks` to generate detailed task breakdown
2. Implement tasks following TDD workflow
3. Create PR with tests and documentation
4. Request code review from team

---

## References

- Feature Spec: [spec.md](./spec.md)
- Research: [research.md](./research.md)
- Data Model: [data-model.md](./data-model.md)
- API Contracts: [contracts/](./contracts/)
- Constitution: [.specify/memory/constitution.md](../../.specify/memory/constitution.md)
- CLAUDE.md: [CLAUDE.md](../../CLAUDE.md)
