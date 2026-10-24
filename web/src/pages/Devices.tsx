import { PageHeader } from '../layout/PageHeader'
import { PhoneIcon } from '../layout/icons'
import { Card } from '../ui/Card'
import { EmptyState } from '../ui/EmptyState'

/** Placeholder until W5 (devices and QR pairing). */
export function Devices() {
  return (
    <>
      <PageHeader title="Devices" description="Android phones paired with your account." />
      <Card padding="none">
        <EmptyState
          icon={<PhoneIcon />}
          title="Coming in the next step"
          description="Pairing a phone with a QR code lands here shortly."
        />
      </Card>
    </>
  )
}
