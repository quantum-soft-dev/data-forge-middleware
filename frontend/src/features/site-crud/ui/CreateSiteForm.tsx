/**
 * CreateSiteForm - Form for creating a new site with password generation.
 *
 * Features:
 * - Site name and password input fields
 * - Password generation button
 * - Form validation with Zod + React Hook Form
 * - Automatic site creation via TanStack Query mutation
 *
 * Feature: 007-adding-a-site (T034, US1)
 */

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Button } from '@/shared/ui/ui/button';
import { Input } from '@/shared/ui/ui/input';
import { Label } from '@/shared/ui/ui/label';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/ui/ui/card';
import { Alert, AlertDescription } from '@/shared/ui/ui/alert';
import { generatePassword } from '@/shared/lib/password-generator';
import { CreateSiteFormSchema, type CreateSiteFormData } from '../model/schemas';
import { useCreateSite, useCreateAdminSite } from '../model/queries';
import { Loader2, RefreshCw, Copy, Check } from 'lucide-react';
import { toast } from 'sonner';
import { SiteCreatedDialog } from './SiteCreatedDialog';

interface CreateSiteFormProps {
  /**
   * Account ID for admin context.
   * If provided, creates site for specified account (admin operation).
   * If not provided, creates site for authenticated user (user operation).
   */
  accountId?: string;

  /**
   * Callback when site is successfully created.
   * Receives the created site data and plaintext password.
   */
  onSuccess?: (data: { site: any; password: string }) => void;

  /**
   * Whether to show the form in a card layout.
   * @default true
   */
  showCard?: boolean;
}

export function CreateSiteForm({ accountId, onSuccess, showCard = true }: CreateSiteFormProps) {
  const [generatedPassword, setGeneratedPassword] = useState<string | null>(null);
  const [passwordCopied, setPasswordCopied] = useState(false);
  const [createdSiteData, setCreatedSiteData] = useState<{
    site: any;
    password: string;
  } | null>(null);
  const [showSuccessDialog, setShowSuccessDialog] = useState(false);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
    reset,
  } = useForm<CreateSiteFormData>({
    resolver: zodResolver(CreateSiteFormSchema),
    defaultValues: {
      siteName: '',
      displayName: '',
      password: '',
    },
  });

  // Use admin or user mutation based on context
  const userCreateMutation = useCreateSite();
  const adminCreateMutation = accountId ? useCreateAdminSite(accountId) : null;
  const createSiteMutation = adminCreateMutation || userCreateMutation;

  const passwordValue = watch('password');

  const handleGeneratePassword = () => {
    const newPassword = generatePassword();
    setValue('password', newPassword, { shouldValidate: true });
    setGeneratedPassword(newPassword);
    setPasswordCopied(false);
  };

  const handleCopyPassword = async () => {
    const password = passwordValue || generatedPassword;
    if (!password) return;

    try {
      await navigator.clipboard.writeText(password);
      setPasswordCopied(true);
      toast.success('Password copied to clipboard');
      setTimeout(() => setPasswordCopied(false), 2000);
    } catch (err) {
      toast.error('Failed to copy password');
    }
  };

  const onSubmit = async (data: CreateSiteFormData) => {
    try {
      const result = await createSiteMutation.mutateAsync({
        siteName: data.siteName,
        displayName: data.displayName,
        password: data.password || undefined,
      });

      toast.success(`Site "${data.siteName}" created successfully!`);

      // Store created site data and show success dialog
      setCreatedSiteData(result);
      setShowSuccessDialog(true);

      // Call success callback with result
      onSuccess?.(result);

      // Reset form
      reset();
      setGeneratedPassword(null);
      setPasswordCopied(false);
    } catch (error: any) {
      const errorMessage = error?.response?.data?.message || error?.message || 'Failed to create site';
      toast.error(errorMessage);
    }
  };

  const formContent = (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {/* Site Name field */}
      <div className="space-y-2">
        <Label htmlFor="siteName">
          Site Name <span className="text-destructive">*</span>
        </Label>
        <Input
          id="siteName"
          placeholder="site-1"
          {...register('siteName')}
          disabled={createSiteMutation.isPending}
          aria-invalid={errors.siteName ? 'true' : 'false'}
          aria-describedby={errors.siteName ? 'siteName-error' : undefined}
        />
        {errors.siteName && (
          <p id="siteName-error" className="text-sm text-destructive">
            {errors.siteName.message}
          </p>
        )}
        <p className="text-sm text-muted-foreground">
          Enter the site name (1-255 characters, unicode allowed)
        </p>
      </div>

      {/* Display Name field */}
      <div className="space-y-2">
        <Label htmlFor="displayName">
          Display Name <span className="text-destructive">*</span>
        </Label>
        <Input
          id="displayName"
          placeholder="My Website"
          {...register('displayName')}
          disabled={createSiteMutation.isPending}
          aria-invalid={errors.displayName ? 'true' : 'false'}
          aria-describedby={errors.displayName ? 'displayName-error' : undefined}
        />
        {errors.displayName && (
          <p id="displayName-error" className="text-sm text-destructive">
            {errors.displayName.message}
          </p>
        )}
        <p className="text-sm text-muted-foreground">
          Human-readable name for the site (2-100 characters)
        </p>
      </div>

      {/* Password field */}
      <div className="space-y-2">
        <Label htmlFor="password">
          Password <span className="text-muted-foreground">(optional)</span>
        </Label>
        <div className="flex gap-2">
          <Input
            id="password"
            type="text"
            placeholder="Leave empty to generate automatically"
            {...register('password')}
            disabled={createSiteMutation.isPending}
            aria-invalid={errors.password ? 'true' : 'false'}
            aria-describedby={errors.password ? 'password-error' : undefined}
            className="flex-1"
          />
          <Button
            type="button"
            variant="outline"
            size="icon"
            onClick={handleGeneratePassword}
            disabled={createSiteMutation.isPending}
            title="Generate password"
          >
            <RefreshCw className="h-4 w-4" />
          </Button>
          {passwordValue && (
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={handleCopyPassword}
              disabled={createSiteMutation.isPending}
              title="Copy password"
            >
              {passwordCopied ? (
                <Check className="h-4 w-4 text-green-600" />
              ) : (
                <Copy className="h-4 w-4" />
              )}
            </Button>
          )}
        </div>
        {errors.password && (
          <p id="password-error" className="text-sm text-destructive">
            {errors.password.message}
          </p>
        )}
        <p className="text-sm text-muted-foreground">
          8-12 alphanumeric characters. Leave empty to auto-generate.
        </p>
      </div>

      {/* Display generated password warning */}
      {generatedPassword && (
        <Alert>
          <AlertDescription>
            Password generated: <strong className="font-mono">{generatedPassword}</strong>
            <br />
            <span className="text-muted-foreground text-xs">
              Make sure to copy and save it - you won't see it again!
            </span>
          </AlertDescription>
        </Alert>
      )}

      {/* Submit button */}
      <div className="flex justify-end gap-2">
        <Button
          type="button"
          variant="outline"
          onClick={() => {
            reset();
            setGeneratedPassword(null);
          }}
          disabled={createSiteMutation.isPending}
        >
          Clear
        </Button>
        <Button type="submit" disabled={createSiteMutation.isPending}>
          {createSiteMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          Create Site
        </Button>
      </div>
    </form>
  );

  const content = showCard ? (
    <Card>
      <CardHeader>
        <CardTitle>Create New Site</CardTitle>
        <CardDescription>
          Add a new site to monitor. A password will be generated automatically if not provided.
        </CardDescription>
      </CardHeader>
      <CardContent>{formContent}</CardContent>
    </Card>
  ) : (
    formContent
  );

  return (
    <>
      {content}

      {/* Success Dialog */}
      {createdSiteData && (
        <SiteCreatedDialog
          open={showSuccessDialog}
          onOpenChange={setShowSuccessDialog}
          site={createdSiteData.site}
          password={createdSiteData.password}
          accountId={accountId || createdSiteData.site.accountId}
        />
      )}
    </>
  );
}
