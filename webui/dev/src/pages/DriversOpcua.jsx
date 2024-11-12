import React, { useEffect, useState } from "react";
import { usePageTitle } from "../utils/PageTitleContext";
import {
  CloseOutlined,
  CheckOutlined,
  PlusOutlined,
  MenuOutlined,
} from "@ant-design/icons";
import {
  Button,
  Card,
  Col,
  Row,
  Form,
  Input,
  Switch,
  Dropdown,
  Menu,
  message,
  Select,
  Tooltip,
} from "antd";
import { useAuth } from "../utils/auth";
import ShowMoreLogsPopup from "../components/ShowMoreLogsPopup";

const { Option } = Select;

const handleMenuClick = async (key, id, auth, fetchData) => {
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}`;
  let mutation = "";

  if (key === "enable") {
    mutation = `
      mutation {
        enableComponent(type: OpcUaDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: OpcUaDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: OpcUaDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  }

  if (mutation) {
    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query: mutation }),
      });

      const data = await response.json();
      console.log("Mutation query:", mutation);
      console.log("Mutation response:", data);

      if (data.errors) {
        message.error(data.errors[0].message);
      } else {
        message.success(
          `${key.charAt(0).toUpperCase() + key.slice(1)} operation successful!`
        );

        setTimeout(async function () {
          fetchData();
        }, 250);
      }
    } catch (error) {
      console.error("Error sending mutation to API:", error);
      message.error("Operation failed!");
    }
  }
};

export function DriversOpcua() {
  const [isPopupVisible, setIsPopupVisible] = useState(false);
  const [selectedItem, setSelectedItem] = useState(null);
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}`;
  const [form] = Form.useForm();
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [apiData, setApiData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isFormVisible, setIsFormVisible] = useState(false);

  const fetchData = async () => {
    const query = `{
      getComponents(group: Driver, type: OpcUaDriver) {
        id
        type
        status
        config
        messages(last: 1) {
          time
          level
          name
          message
        }
      }
    }`;

    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query }),
      });

      const data = await response.json();
      setApiData(data?.data?.getComponents || []);
      console.log("API response:", data);
    } catch (error) {
      console.error("Error fetching data from API:", error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setTitle("OPC UA Drivers Configuration");
    fetchData();
  }, []);

  const handleToggleForm = () => {
    if (isFormVisible) {
      form.resetFields();
    }
    setIsFormVisible(!isFormVisible);
  };

  const handleSubmit = async (values) => {
    const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
    const serverURL = `http://${endpoint}`;
    console.log("Form values:", values);

    const dataObject = {
      Id: values.items[0]?.Id,
      Enabled: values.items[0]?.Enabled || false,
      LogLevel: values.items[0]?.LogLevel || "INFO",
      EndpointUrl: values.items[0]?.Endpoint || "opc.tcp://127.0.0.1:4849/",
      UpdateEndpointUrl: values.items[0]?.UpdateEndpointUrl || true,
      SecurityPolicyUri:
        values.items[0]?.SecurityPolicyUri ||
        "http://opcfoundation.org/UA/SecurityPolicy#None",
      // Ensure these values are numbers
      KeepAliveFailuresAllowed:
        Number(values.items[0]?.KeepAliveFailuresAllowed) || 0,
      SubscriptionSamplingInterval:
        Number(values.items[0]?.SubscriptionSamplingInterval) || 0.0,
    };

    // Convert the object to a JSON string
    const configString = JSON.stringify(dataObject);

    const query = `
      mutation {
        createComponent(type: OpcUaDriver, config: "${configString.replace(
          /"/g,
          '\\"'
        )}") {
          ok
          code
          message
        }
      }
    `;

    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query }),
      });

      const data = await response.json();
      if (data.errors) {
        message.error(data.errors[0].message);
      } else {
        message.success("Component created successfully!");

        // Reset the form fields after successful creation
        form.resetFields();

        setTimeout(async function () {
          fetchData();
        }, 250);

        //fetchData(); // Refresh data after creation
      }
    } catch (error) {
      console.error("Error sending data to API:", error);
      message.error("Creation failed!");
    }
  };

  const handleShowPopup = (item) => {
    setSelectedItem(item);
    setIsPopupVisible(true);
  };

  const handleClosePopup = () => {
    setIsPopupVisible(false);
    setSelectedItem(null);
  };

  return (
    <>
      <p></p>
      <div style={{}}>
        <Button
          onClick={handleToggleForm}
          className="custom-button"
          style={{
            width: "100%",
            display: "flex",
            justifyContent: "center",
            alignItems: "center",
          }}
        >
          {isFormVisible ? (
            <CloseOutlined style={{ fontSize: "16px" }} />
          ) : (
            <PlusOutlined style={{ fontSize: "16px" }} />
          )}
        </Button>
      </div>
      {isFormVisible && (
        <Form
          labelCol={{ span: 8 }}
          wrapperCol={{ span: 16 }}
          form={form}
          name="opcuadriver_form"
          style={{
            maxWidth: "100%",
            width: "100%",
            backgroundColor: "#f1f1f1",
          }}
          autoComplete="off"
          initialValues={{ items: [{}] }}
          onFinish={handleSubmit}
          labelAlign="left"
        >
          <Form.List name="items">
            {(fields, { add, remove }) => (
              <div
                style={{ display: "flex", flexDirection: "column", rowGap: 16 }}
              >
                {fields.map((field) => (
                  <div
                    key={field.key}
                    style={{
                      border: "1px solid #d9d9d9",
                      padding: "16px",
                      borderRadius: "4px",
                    }}
                  >
                    <Row gutter={16}>
                      <Col span={12}>
                        <Form.Item
                          label="Id:"
                          name={[field.name, "Id"]}
                          rules={[
                            { required: true, message: "Id is required" },
                          ]}
                          labelCol={{ span: 6 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Input placeholder="Component Name" />
                        </Form.Item>
                        <Form.Item
                          label="Log Level:"
                          name={[field.name, "LogLevel"]}
                          initialValue="INFO"
                          labelCol={{ span: 6 }}
                          wrapperCol={{ span: 16 }}
                        >
                          <Select placeholder="Select Log Level">
                            <Option value="INFO">INFO</Option>
                            <Option value="ALL">ALL</Option>
                            <Option value="SEVERE">SEVERE</Option>
                            <Option value="WARNING">WARNING</Option>
                            <Option value="CONFIG">CONFIG</Option>
                            <Option value="FINE">FINE</Option>
                            <Option value="FINER">FINER</Option>
                            <Option value="FINEST">FINEST</Option>
                            <Option value="OFF">OFF</Option>
                          </Select>
                        </Form.Item>
                        <Form.Item
                          label="Endpoint:"
                          name={[field.name, "Endpoint"]}
                          rules={[
                            {
                              required: true,
                              message: "Please input a valid Endpoint URL!",
                            },
                          ]}
                          labelCol={{ span: 6 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Input placeholder="opc.tcp://192.168.1.3:62540/server" />
                        </Form.Item>
                        <Form.Item
                          label="Subscription Sampling Interval:"
                          name={[field.name, "SubscriptionSamplingInterval"]}
                          labelCol={{ span: 10 }}
                          wrapperCol={{ span: 12 }}
                          labelAlign="left"
                        >
                          <Input placeholder="1.5" />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item
                          label="Enabled:"
                          name={[field.name, "Enabled"]}
                          labelCol={{ span: 6 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Switch
                            checkedChildren={<CheckOutlined />}
                            unCheckedChildren={<CloseOutlined />}
                          />
                        </Form.Item>
                        <Form.Item
                          label="Update Endpoint Url:"
                          name={[field.name, "UpdateEndpointUrl"]}
                          labelCol={{ span: 6 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Switch
                            checkedChildren={<CheckOutlined />}
                            unCheckedChildren={<CloseOutlined />}
                          />
                        </Form.Item>
                        <Form.Item
                          label="Security Policy Uri:"
                          name={[field.name, "SecurityPolicyUri"]}
                          labelCol={{ span: 6 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Select
                            placeholder="Select Security Policy"
                            defaultValue="http://opcfoundation.org/UA/SecurityPolicy#None"
                          >
                            <Option value="http://opcfoundation.org/UA/SecurityPolicy#None">
                              None
                            </Option>
                            <Option
                              value="http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256"
                              disabled
                            >
                              Basic256Sha256
                            </Option>
                            <Option
                              value="http://opcfoundation.org/UA/SecurityPolicy#Aes256_Sha256_RsaPss"
                              disabled
                            >
                              Aes256_Sha256_RsaPss
                            </Option>
                            <Option
                              value="http://opcfoundation.org/UA/SecurityPolicy#Aes128_Sha256_RsaOaep"
                              disabled
                            >
                              Aes128_Sha256_RsaOaep
                            </Option>
                          </Select>
                        </Form.Item>
                        <Form.Item
                          label="Keep Alive Failures Allowed:"
                          name={[field.name, "KeepAliveFailuresAllowed"]}
                          labelCol={{ span: 10 }}
                          wrapperCol={{ span: 12 }}
                          labelAlign="left"
                        >
                          <Input placeholder="2" />
                        </Form.Item>
                      </Col>
                    </Row>
                    <div style={{ textAlign: "center" }}>
                      <Button
                        type="submit"
                        className="btn btn-success"
                        htmlType="submit"
                        style={{
                          margin: "0 auto",
                          textAlign: "center",
                          padding: "10px 20px",
                          lineHeight: "normal",
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "center",
                        }}
                      >
                        Add Configuration
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Form.List>
        </Form>
      )}
      <Row gutter={[16, 16]} style={{ marginTop: "16px" }}>
        {apiData && Array.isArray(apiData) && apiData.length > 0 ? (
          apiData.map((item) => (
            <Col span={6} key={item.id}>
              <Card
                title={
                  item.id.length > 20 ? (
                    <Tooltip title={item.id}>
                      <div className="card-title">
                        {item.id.substring(0, 20) + "..."}
                      </div>
                    </Tooltip>
                  ) : (
                    <div className="card-title">{item.id}</div>
                  )
                }
                bordered={false}
                extra={
                  <Dropdown
                    overlay={
                      <Menu>
                        <Menu.Item
                          key="enable"
                          onClick={() =>
                            handleMenuClick(
                              "enable",
                              item.id,
                              auth,
                              setApiData,
                              fetchData
                            )
                          }
                        >
                          Enable
                        </Menu.Item>
                        <Menu.Item
                          key="disable"
                          onClick={() =>
                            handleMenuClick(
                              "disable",
                              item.id,
                              auth,
                              setApiData,
                              fetchData
                            )
                          }
                        >
                          Disable
                        </Menu.Item>
                        <Menu.Item
                          key="delete"
                          onClick={() =>
                            handleMenuClick(
                              "delete",
                              item.id,
                              auth,
                              setApiData,
                              fetchData
                            )
                          }
                          danger
                        >
                          Delete
                        </Menu.Item>
                      </Menu>
                    }
                    trigger={["click"]}
                  >
                    <Button className="btn-action-custom">
                      <MenuOutlined />
                    </Button>
                  </Dropdown>
                }
                style={{ backgroundColor: "#d5f5d5" }}
              >
                <p>
                  <strong>Type:</strong> {item.type}
                </p>
                <p>
                  <strong>Status:</strong> {item.status}
                </p>
                <p>
                  <strong>Endpoint:</strong>{" "}
                  {JSON.parse(item.config).EndpointUrl}
                </p>
                <p>
                  <strong>Log Level:</strong> {JSON.parse(item.config).LogLevel}
                </p>
                <div style={{ display: "flex", justifyContent: "center" }}>
                  <Button
                    onClick={() => handleShowPopup(item)}
                    className="custom-button"
                  >
                    Show Logs
                  </Button>
                </div>
              </Card>
            </Col>
          ))
        ) : (
          <></>
        )}
      </Row>
      {isPopupVisible && selectedItem && (
        <ShowMoreLogsPopup
          visible={isPopupVisible}
          onClose={handleClosePopup}
          type={selectedItem.type}
          id={selectedItem.id}
        />
      )}
    </>
  );
}
