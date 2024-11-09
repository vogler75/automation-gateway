import React, { useState, useEffect } from "react";
import Container from "react-bootstrap/Container";
import Row from "react-bootstrap/Row";
import Col from "react-bootstrap/Col";
import { useAuth } from "../utils/auth";
import { LeftSideNav } from "../components/LeftSideNav";
import { Outlet } from "react-router-dom";
import { PageTitleContext, usePageTitle } from "../utils/PageTitleContext";

export function Home() {
  const auth = useAuth();
  const [currentTime, setCurrentTime] = useState(new Date());

  useEffect(() => {
    const intervalId = setInterval(() => {
      setCurrentTime(new Date());
    }, 1000);

    return () => clearInterval(intervalId);
  }, []);

  const formattedDateTime = `${currentTime.toLocaleDateString()} ${currentTime.toLocaleTimeString()}`;
  const [title, setTitle] = useState("Welcome!");

  return (
    <PageTitleContext.Provider value={{ title, setTitle }}>
      <Container fluid={true}>
        <Row>
          <Col
            style={{
              paddingLeft: "50px",
              paddingTop: "4px",
              paddingBottom: "4px",
            }}
          >
            <img
              src="/icon.png"
              alt="AG Logo"
              style={{
                width: "10%",
                height: "auto",
              }}
            />
          </Col>
          <Col
            style={{
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              textAlign: "center",
              paddingTop: "4px",
              paddingBottom: "4px",
            }}
          >
            {title}
          </Col>
          <Col
            style={{
              display: "flex",
              flexDirection: "column",
              alignItems: "flex-end",
              justifyContent: "center",
              textAlign: "right",
              paddingRight: "20px",
              paddingTop: "4px",
              paddingBottom: "4px",
            }}
          >
            <label>
              <b>Welcome {auth.user}</b>
              <br />
              {formattedDateTime}
            </label>
          </Col>
        </Row>
        <Row>
          <Col className="spacer">.</Col>
        </Row>
        <Row>
          <Col sm="auto" className="p-0" style={{ height: "100%" }}>
            <LeftSideNav />
          </Col>
          <Col className="columns" sm="auto" style={{ flex: 1 }}>
            <Outlet />
          </Col>
        </Row>
      </Container>
    </PageTitleContext.Provider>
  );
}
