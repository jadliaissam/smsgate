import { PageHeader } from '../layout/PageHeader'
import { KeyIcon } from '../layout/icons'
import { Card } from '../ui/Card'
import { EmptyState } from '../ui/EmptyState'

/** Placeholder until W6 (API keys). */
export function ApiKeys() {
  return (
    <>
      <PageHeader title="API keys" description="Keys your servers use to call the SMSGate API." />
      <Card padding="none">
        <EmptyState
          icon={<KeyIcon />}
          title="Coming in the next step"
          description="Creating and revoking API keys lands here shortly."
        />
      </Card>
    </>
  )
}
