package messages

import (
	"reflect"
	"testing"

	"github.com/smsgate/backend/internal/db/dbq"
)

// The create path never reads the body back from the database: the inserted
// row and the idempotent-replay lookup select explicit body-less columns.
func TestCreateRowsHaveNoBody(t *testing.T) {
	for _, v := range []any{dbq.InsertMessageRow{}, dbq.GetMessageByIdemRow{}} {
		if _, ok := reflect.TypeOf(v).FieldByName("Body"); ok {
			t.Errorf("%T selects body", v)
		}
	}
}
