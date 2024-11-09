import React, { useState } from "react";
import Alert from "react-bootstrap/Alert";

export function AlertDismissible({ show, onClose }) {
  const [alertShow, setAlertShow] = useState(show);
  const handleClose = () => {
    setAlertShow(false);
    if (onClose) {
      onClose();
    }
  };

  return (
    <>
      <Alert
        show={show ? true : false}
        variant="danger"
        dismissible
        onClose={handleClose}
        transition={true}
      >
        <p>
          <b>Login error: </b> <br />
          The username and password you entered did not match our records.
          Please double-check and try again.
        </p>
      </Alert>
    </>
  );
}
